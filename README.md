# wely-users

Service de gestion des profils utilisateurs et **pont entre l'identité Keycloak et l'identité métier** de la plateforme [Wely Calendar](https://github.com/WelyLabs/wely-platform).

C'est le seul service qui écrit dans PostgreSQL, le seul qui détient un accès à l'Admin API Keycloak (gardé pour les futures modifications de compte, inutilisé à l'inscription), et le producteur de l'événement `USER_CREATED` dont dépend le graphe social. Cet événement part par un **outbox transactionnel** : il est écrit en base dans la même transaction que l'utilisateur, puis relayé vers Kafka.

---

## Rôle

| | |
|---|---|
| **Port** | 8082 |
| **Préfixe** | `/user-service` (exposé via la gateway sur `/api/v1/user-service/**`) |
| **Base** | PostgreSQL via R2DBC |
| **Produit** | `USER_CREATED` sur Kafka |
| **Dépend de** | Keycloak, qui l'appelle à l'inscription et à chaque token ; client Admin API (`client_credentials`) configuré pour les futures modifications de compte · `wely-media` pour les photos de profil |

---

## Stack

Java 25 · Spring Boot 4 · WebFlux · Spring Data R2DBC · Spring Cloud Stream (Kafka) · MapStruct · Lombok · OAuth2 Resource Server + Client

---

## Architecture

Architecture hexagonale. Le domaine déclare trois ports ; l'infrastructure fournit trois adaptateurs.

```
                  ┌──────────────────────────────────────┐
  HTTP            │           application/rest           │
  ───────────────▶│   ProfileController                  │
                  └──────────────────┬───────────────────┘
                                     │
                  ┌──────────────────▼───────────────────┐
                  │              domain/                 │
                  │                                      │
                  │   models/   BusinessUser (record)    │
                  │   services/ UserService  (POJO)      │
                  │                                      │
                  │   ports/                             │
                  │     UserRepository ──────────┐       │
                  │     IdentityProvider ────────┼─┐     │
                  │     MediaStore ──────────────┼─┼─┐   │
                  └──────────────────────────────┼─┼─┼───┘
                                                 │ │
                  ┌──────────────────────────────▼─▼─────┐
                  │           infrastructure/            │
                  │                                      │
                  │  persistence/  R2dbcUserRepositoryAdapter (@Transactional)
                  │                → UserR2dbcRepository │
                  │                → R2dbcOutboxEventStoreAdapter
                  │                  → OutboxEventR2dbcRepository
                  │  identity/     IdentityAuthAdapter   │
                  │                → KeycloakAdminApi    │
                  │  media/        MediaApiAdapter → wely-media
                  │  messaging/    OutboxRelay           │
                  │                → KafkaOutboxDispatcher
                  │                → StreamBridge        │
                  └──────────┬─────────┬─────────┬───────┘
                             ▼         ▼         ▼
                       PostgreSQL  Keycloak    Kafka
```

Les deux ports sont fournis par l'infrastructure. Le domaine se contente d'enregistrer un utilisateur : prévenir les autres services (`USER_CREATED`) est un **événement d'intégration**, une conséquence du découpage en microservices et non une règle métier. C'est donc l'adaptateur de persistance qui l'écrit, dans la même transaction que l'utilisateur.

`UserService` est un **POJO sans annotation Spring**, instancié par `UsersApplicationConfig` :

```java
@Bean
public UserService userService(UserRepository repository, IdentityProvider identityProvider) {
    return new UserService(repository, identityProvider);
}
```

Il se teste donc sans contexte Spring, avec deux implémentations d'interfaces.

---

## Le cœur du service : créer à l'inscription, lire à la connexion

Deux opérations, appelées par Keycloak avant qu'un token existe, toutes deux protégées par `X-Internal-Secret` :

- **`provisionUser`** crée l'utilisateur pendant l'inscription, depuis l'étape `BusinessUserRegistration` de [wely-identity](https://github.com/WelyLabs/wely-identity). Keycloak envoie l'identité dans la requête : pas d'appel à son API Admin. Si cette création échoue, Keycloak annule le compte.
- **`findBusinessIdByKeycloakId`** est appelée par le mapper à chaque émission de token. Elle ne fait que lire : un UUID Keycloak inconnu répond 404, et la connexion est refusée.

```mermaid
sequenceDiagram
    participant KC as Keycloak (inscription)
    participant C as ProfileController
    participant S as UserService
    participant DB as PostgreSQL
    participant R as OutboxRelay
    participant K as Kafka

    KC->>C: POST /profile/provision {keycloakId, username, firstName, lastName}
    C->>S: provisionUser(keycloakId, identité)
    S->>DB: findIdByKeycloakId
    alt déjà créé (appel rejoué)
        DB-->>S: UUID
    else nouvel utilisateur
        S->>S: generateUniqueHashtag(username)
        loop tant que le couple est pris
            S->>DB: existsByUserNameAndHashtag
        end
        rect rgb(232, 242, 237)
            note over S,DB: une seule transaction
            S->>DB: save(BusinessUser)
            S->>DB: INSERT outbox_event (USER_CREATED)
        end
    end
    S-->>KC: businessId

    R->>DB: SELECT … FOR UPDATE SKIP LOCKED
    R->>K: publie USER_CREATED
    R->>DB: UPDATE published_at
```

`provisionUser` est **idempotente** sur l'UUID Keycloak : un appel rejoué renvoie l'utilisateur existant, et de deux appels simultanés, celui qui bute sur la contrainte `keycloak_id` renvoie l'identifiant du gagnant.

La publication **ne fait pas partie de la requête** : Keycloak attend l'identifiant métier, pas un aller-retour vers le broker, et un Kafka indisponible fait grossir une file au lieu de perdre un événement.

Créer l'utilisateur à l'inscription plutôt qu'à la première émission de token sépare l'écriture de la lecture : jusqu'au 2026-10-10, la création se faisait pendant l'émission du token, dans un délai de 3 s, et une première connexion à froid finissait sur une page blanche.

### Les utilisateurs orphelins

Si `wely-users` crée l'utilisateur mais que Keycloak annule quand même le compte (réponse arrivée après le délai de 10 s, ou échec de Keycloak juste après), il reste un utilisateur sans compte. **`OrphanUserReaper`**, une boucle de fond comme le relais de l'outbox, s'en charge toutes les heures :

- il lit les utilisateurs de plus de 15 min dont le compte n'a jamais été confirmé (`identity_checked_at` nul) ;
- il demande chacun à l'API Admin de Keycloak : un compte trouvé est marqué confirmé, et n'est plus jamais revérifié ; un compte inconnu (404) fait **supprimer** l'utilisateur, avec un `USER_DELETED` écrit dans l'outbox dans la même transaction ;
- une panne de Keycloak n'est pas une preuve d'absence : l'utilisateur reste à vérifier au passage suivant.

`wely-social` consomme `USER_DELETED` et supprime le nœud. Les amis fictifs de la démo n'ont pas de compte Keycloak : le jeu de données les marque confirmés.

### Le hashtag

Un utilisateur est identifié publiquement par `Pseudo#1234`, à la manière de Discord. Le hashtag est tiré au hasard entre 1000 et 9999 et retiré tant que le couple `(user_name, hashtag)` est déjà pris. L'unicité est **garantie par la base**, pas par le code :

```sql
CONSTRAINT unique_user_identity UNIQUE (user_name, hashtag)
```

Le tirage aléatoire évite la contention d'un compteur séquentiel partagé ; la contrainte SQL reste le dernier rempart en cas de course.

---

## API

Toutes les routes sont préfixées par `/user-service` et exposées via la gateway sur `/api/v1/user-service/**`.

| Méthode | Route | Auth | Description |
|---|---|---|---|
| `GET` | `/profile` | JWT | Profil de l'utilisateur courant (identité lue dans le claim `businessId`) |
| `GET` | `/profile/about` | JWT | Présentation de l'utilisateur courant : bio (160 caractères) et centres d'intérêt (5 au plus, 24 caractères chacun) |
| `PUT` | `/profile/about` | JWT | La remplace ; espaces nettoyés, doublons retirés (« Rando » et « rando »), refus au-delà des limites (`USR-BUS-008`, 400) plutôt que coupure |
| `GET` | `/users/{userId}/profile` | JWT | Profil d'un ami (identité, date d'inscription, bio, centres d'intérêt). Amitié vérifiée auprès de `wely-social` avec le jeton de l'appelant : 403 `USR-BUS-009` sinon, 404 `USR-BUS-001` si l'utilisateur est inconnu, 502 `USR-TEC-005` si `wely-social` ne répond pas. Son propre id renvoie son profil sans appel |
| `POST` | `/profile/provision` | `X-Internal-Secret` | **Interne.** Crée l'utilisateur à l'inscription, depuis l'étape d'inscription de Keycloak. Idempotent sur l'UUID Keycloak |
| `GET` | `/profile/resolve/{keycloakId}` | `X-Internal-Secret` | **Interne.** Lit le `businessId` d'un UUID Keycloak ; 404 s'il est inconnu |
| `POST` | `/profile/picture` | JWT | Remplace la photo de l'utilisateur courant (multipart `file`, 5 Mo au plus) et répond une URL signée |
| `POST` | `/avatars` | JWT | URL des photos de 100 utilisateurs au plus, `{ userId: url }` ; les utilisateurs sans photo sont absents |

`/profile/provision` et `/profile/resolve/**` sont les seuls endpoints exemptés de l'authentification JWT : Keycloak les appelle pendant l'inscription et pendant l'émission du token, donc avant qu'un token existe. Il est protégé par un secret partagé vérifié dans un `WebFilter` dédié.

### OpenAPI

La spécification est générée par `springdoc-openapi` et servie sans jeton :

| | |
|---|---|
| Spec JSON | `http://localhost:8082/v3/api-docs` |
| Swagger UI | `http://localhost:8082/swagger-ui.html` |

Ces deux chemins ne sont **pas** routés par la gateway, et le Service est en `ClusterIP` : rien
hors du cluster ne peut les atteindre. La documentation reste donc active en permanence — c'est
la topologie réseau qui la protège, pas un drapeau.

> Le préfixe de chemin du service est appliqué par package (`…application.rest`) et non par
> annotation. Sélectionner sur `@RestController` attrapait aussi le contrôleur de springdoc, ce
> qui déplaçait la spec en `/user-service/v3/api-docs` derrière l'authentification.

### Photos de profil : via wely-media

Le service ne parle plus au stockage objet : **les photos passent par `wely-media`**, un service interne qui les normalise (carré central de 512 px, ré-encodé en JPEG, métadonnées retirées), les stocke en privé et signe leurs URL. `wely-users` ne garde que la **clé** (`avatars/<userId>/<uuid>.jpg`) ; l'URL, valable une heure, est calculée à l'affichage. Chaque appel porte l'en-tête `X-Internal-Secret`.

- **Envoi** : `store(clé, fichier, AVATAR)`, puis l'utilisateur est pointé sur la clé. Si la base refuse, le nouveau fichier est supprimé ; l'ancien n'est supprimé qu'ensuite, et son échec ne laisse qu'un fichier orphelin.
- **Affichage** : les clés d'un écran sont signées en **un seul appel**. Les chemins servis par l'application web (avatars de démo) sont rendus tels quels.
- **wely-media indisponible à la signature** : `/avatars` répond quand même, sans les photos stockées (les avatars de démo restent) ; l'envoi d'une photo enregistrée mais non signée répond `profilePicUrl: null`, et le front relit l'URL via `/avatars`.
- Image refusée (`MED-BUS-001`) → `USR-BUS-006` ; trop lourde (`MED-BUS-002`) → `USR-BUS-007` ; toute autre erreur, connexion comprise → `USR-TEC-004`.

### Modèle

```java
public record BusinessUser(
        UUID id,
        String userName,
        Integer hashtag,
        String firstName,
        String lastName,
        String profilePicUrl,
        LocalDateTime joinedDate
) {}
```

### Schéma

```sql
CREATE TABLE app_user (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    keycloak_id     TEXT UNIQUE NOT NULL,
    user_name       TEXT NOT NULL,
    hashtag         INTEGER NOT NULL,
    first_name      TEXT,
    last_name       TEXT,
    profile_pic_url TEXT,
    joined_date     TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    identity_checked_at TIMESTAMPTZ,        -- V3
    bio             TEXT,                   -- V4
    interests       TEXT[] NOT NULL DEFAULT '{}',  -- V4
    CONSTRAINT unique_user_identity UNIQUE (user_name, hashtag)
);

CREATE TABLE outbox_event (
    id           BIGSERIAL PRIMARY KEY,   -- ORDER BY id = ordre de publication
    aggregate_id UUID        NOT NULL,    -- clé de partition Kafka
    type         TEXT        NOT NULL,    -- destination + classe du payload
    payload      TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,             -- NULL = en attente
    attempts     INTEGER     NOT NULL DEFAULT 0,
    last_error   TEXT
);

CREATE INDEX idx_outbox_event_pending ON outbox_event (id) WHERE published_at IS NULL;
```

Les deux tables sont décrites dans `db/migration/`, **volontairement hors du classpath** pour qu'aucune initialisation Spring ne les exécute à l'insu de qui que ce soit. `V2__create_outbox_event.sql` doit être appliqué **avant** de déployer la version qui écrit dedans : l'insertion fait partie du provisioning, donc une table absente casse l'inscription au lieu de la dégrader. `V3__add_identity_checked_at.sql` doit l'être avant la version qui nettoie les orphelins, et avant le jeu de données de démo qui remplit la colonne. `V4__add_profile_about.sql` (bio et centres d'intérêt) doit l'être avant la version qui les lit : sans les colonnes, la page de profil ne charge plus sa présentation.

---

## Événement publié

**Topic** `USER_CREATED` · clé de partition `payload.userId` · `acks=all`

```json
{
  "userId": "550e8400-e29b-41d4-a716-446655440000",
  "userName": "theo",
  "hashtag": 4271,
  "profilePicUrl": null
}
```

Consommé par [`wely-social`](https://github.com/WelyLabs/wely-social), qui crée le nœud correspondant dans Neo4j.

---

## L'outbox transactionnel

### Le problème

Écrire en PostgreSQL et publier sur Kafka sont deux systèmes différents : il n'existe pas de commit qui couvre les deux. Entre le `COMMIT` et le `send()`, un redémarrage de pod, un OOM kill ou un broker injoignable laissait un utilisateur en base **sans nœud social**, définitivement — rien ne réessayait, et rien ne le signalait.

`@Transactional` n'aurait rien réglé : un rollback annule des écritures en base, pas un message déjà parti sur un broker. Il aurait même inversé le défaut, en ajoutant le cas « événement publié pour un utilisateur qui n'existe pas ».

### Le motif

On ne rend pas les deux systèmes atomiques, on **ramène le deuxième dans le premier** :

1. `R2dbcUserRepositoryAdapter.save`, annotée `@Transactional`, insère l'utilisateur puis l'événement `USER_CREATED` dans `outbox_event` : les deux sont validées ensemble, ou aucune.
2. `OutboxRelay`, hors du chemin de requête, lit les lignes non publiées, les envoie, et les marque.

La seule chose à garantir n'est plus que deux systèmes réussissent ensemble, mais qu'**un seul finisse par réussir**.

### Le relais

```
① réclamer   SELECT … WHERE published_at IS NULL … FOR UPDATE SKIP LOCKED
② publier    un message par ligne, dans l'ordre des id
③ marquer    UPDATE published_at
```

- **`FOR UPDATE SKIP LOCKED`** : deux instances prennent des lots disjoints sans s'attendre. Le service tourne à un réplica aujourd'hui, donc ça ne sert à rien — et c'est précisément pour ça que c'est écrit maintenant : passer à deux est une ligne de manifeste, et la double publication que ça provoquerait ne s'annonce nulle part.
- **La fenêtre entre ② et ③ ne peut pas être fermée.** Un processus qui meurt là a envoyé l'événement sans l'avoir noté : il repartira. La livraison est *at-least-once*, et le consommateur doit être idempotent — `wely-social` l'est déjà, son `MERGE` Cypher est idempotent par construction.
- **Message empoisonné** : une ligne illisible bloquerait la tête de file, puisque la lecture suit l'ordre des id. Au-delà de `max-attempts` elle sort de la sélection et reste en table avec son `last_error`.
- **Pas d'ordonnanceur** : `Mono.repeatWhen` plutôt que `@Scheduled` (thread bloquant) ou `Flux.interval` (émet au rythme de l'horloge et déborde dès que le consommateur prend du retard). Le délai se place *entre* deux passes, qui ne peuvent donc pas se chevaucher.
- **Une erreur non rattrapée terminerait le flux** et le relais s'arrêterait définitivement, sans bruit. L'`onErrorResume` est à l'intérieur de la passe répétée, et un gestionnaire d'erreur sur le `subscribe` sert de second filet.

### Ce qu'on garde comme coût

Une table à purger, un délai d'une seconde en moyenne avant publication, et des doublons possibles. En échange, un événement ne se perd plus.

---

## Gestion des erreurs

Deux hiérarchies distinctes — métier et technique — traduites par `GlobalErrorHandler`.

| Code | HTTP | Signification |
|---|---|---|
| `USR-BUS-001` | 404 | Utilisateur introuvable |
| `USR-BUS-002` | 409 | Utilisateur déjà existant pour cette identité |
| `USR-BUS-003` | 409 | Plus aucun hashtag disponible pour ce pseudo |
| `USR-BUS-004` | 409 | Pseudo déjà pris |
| `USR-BUS-005` | 400 | Profil refusé par Keycloak |
| `USR-BUS-006` | 400 | Le fichier n'est pas une image acceptée |
| `USR-BUS-007` | 413 | Photo de plus de 5 Mo |
| `USR-BUS-008` | 400 | Présentation hors limites (bio, centres d'intérêt) |
| `USR-BUS-009` | 403 | Profil d'un utilisateur qui n'est pas un ami |
| `USR-VAL-001` | 400 | Validation du corps de requête, détail par champ |
| `USR-REQ-000` | *repris* | Chemin inconnu, méthode non autorisée |
| `USR-TEC-001` | 502 | Keycloak injoignable |
| `USR-TEC-002` | 502 | Base de données injoignable |
| `USR-TEC-003` | 502 | Kafka injoignable |
| `USR-TEC-004` | 502 | wely-media injoignable ou en erreur |
| `USR-TEC-005` | 502 | wely-social injoignable, en erreur ou au-delà de 5 s |

**502 et non 500 pour les erreurs techniques** : la requête était valide et le service tourne —
c'est une dépendance qui est tombée, et 502 le dit précisément.

Les adaptateurs traduisent les exceptions d'infrastructure (`DataIntegrityViolationException`,
erreurs WebClient) à la frontière : le domaine ne voit jamais d'exception technique.

Toutes les réponses d'erreur sont des `ProblemDetail` (RFC 7807), avec un `code` stable qu'un
client peut tester et un `timestamp` :

```json
{
  "type": "https://welylabs.app/problems/usr-bus-001",
  "title": "User not found",
  "status": 404,
  "detail": "No user matches the given identifier.",
  "instance": "/user-service/profile/8f2c…",
  "code": "USR-BUS-001",
  "timestamp": "2026-09-30T19:23:43.598673Z"
}
```

> `USR-REQ-000` rend le statut d'origine d'une `ResponseStatusException` — 404 sur un
> chemin inconnu, 405 sur une méthode non autorisée. Sans lui, le handler `Exception.class`
> les avalait toutes et **tout chemin inconnu répondait 500**. C'est le genre de défaut qu'un
> test de route nominale ne voit jamais.
---

## Configuration

| Variable | Description |
|---|---|
| `DB_URL` | URL R2DBC PostgreSQL |
| `DB_USERNAME` / `DB_PASSWORD` | Identifiants base |
| `KEYCLOAK_ISSUER_URI` | Issuer **public** — validation de l'émetteur du token |
| `KEYCLOAK_INTERNAL_JWK_SET_URI` | JWKS **interne** au cluster — récupération des clés |
| `KEYCLOAK_INTERNAL_TOKEN_URI` | Endpoint token interne (`client_credentials`) |
| `KEYCLOAK_BASE_URL` | Base de l'Admin API Keycloak |
| `KEYCLOAK_CLIENT_SECRET` | Secret du client `wely-users-api-client` |
| `KAFKA_BOOTSTRAP_SERVER` | Brokers Kafka |
| `KAFKA_KEY` / `KAFKA_SECRET` | Identifiants SASL |
| `OUTBOX_RELAY_ENABLED` | Démarre le relais (`true` par défaut) |
| `MEDIA_API_URL` | URL de `wely-media` (`http://localhost:8085` en profil `dev`) |
| `SOCIAL_API_URL` | URL de `wely-social` (`http://localhost:8083` en profil `dev`), interrogée avec le jeton de l'appelant |
| `MEDIA_API_SECRET` | Secret partagé envoyé à `wely-media` dans `X-Internal-Secret` |

Réglages de l'outbox, dans `application.properties` :

| Propriété | Défaut | Rôle |
|---|---|---|
| `app.outbox.relay.poll-interval` | `2s` | Délai entre deux passes ; la moitié est la latence moyenne de publication |
| `app.outbox.relay.batch-size` | `100` | Lignes réclamées par passe |
| `app.outbox.relay.max-attempts` | `5` | Au-delà, l'événement sort de la file au lieu de la bloquer |
| `app.outbox.purge.interval` | `1h` | Fréquence de la purge |
| `app.outbox.purge.retention` | `7d` | Durée de conservation des lignes publiées |

Voir [Validation du JWT derrière un ingress](https://github.com/WelyLabs/wely-platform#validation-du-jwt-derrière-un-ingress) pour la raison de la dissociation issuer/JWKS.

---

## Démarrage

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Le profil `dev` pointe sur `localhost` (PostgreSQL 5432, Keycloak 8080, Kafka 9092).

Pour lancer **toute la plateforme** (bases, Keycloak, gateway, frontend, les quatre services) en une commande sur un Kubernetes local :

```bash
git clone https://github.com/WelyLabs/wely-gitops-infra && cd wely-gitops-infra
kubectl apply -k overlays/local --server-side
```

---

## Tests

```bash
./mvnw test                    # tests unitaires
./mvnw test jacoco:report      # + couverture → target-maven/site/jacoco/
```

Le domaine est testé sans Spring ; les adaptateurs le sont avec Mockito et `StepVerifier`.

Les classes `*IntegrationTest` démarrent un PostgreSQL avec Testcontainers et sont **optionnelles** : la CI pose `CI=true`, en local il faut `-Dintegration.tests=true` et un démon Docker vivant.

```bash
./mvnw test -Dintegration.tests=true
```

Elles portent sur ce qu'un mock ne peut pas montrer : que `FOR UPDATE SKIP LOCKED` donne bien des lots disjoints à deux transactions concurrentes, et que l'utilisateur et sa ligne d'outbox sont validés ou annulés **ensemble**.

> **Note build :** ce service produit dans `target-maven/` et non `target/` (propriété `build.dir` du `pom.xml`).

---

## Limites connues

- **Pas d'outil de migration.** Les fichiers de `db/migration/` sont appliqués à la main, sans Flyway ni Liquibase. Rien ne vérifie au démarrage que le schéma correspond au code.
- **Pas de réconciliation.** L'outbox empêche une classe de pannes ; il ne dit pas si le graphe social a dérivé pour une autre raison. Comparer le nombre de lignes `app_user` au nombre de nœuds `User` couvrirait *toutes* les causes.
- **Le relais tient sa transaction pendant l'envoi.** Les verrous durent le temps de l'appel au broker. C'est le bon compromis à un événement par inscription, pas à un débit soutenu.
