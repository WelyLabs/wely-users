-- Fichier : src/main/resources/schema.sql

DROP SCHEMA public CASCADE;

-- 2. Recrée le schéma public vide
CREATE SCHEMA public;

-- 3. Accorde les permissions de base sur le nouveau schéma
GRANT ALL ON SCHEMA public TO CURRENT_USER;
GRANT ALL ON SCHEMA public TO PUBLIC;

-- TABLE PRINCIPALE : app_user (renommée pour éviter conflit avec le mot-clé 'user')
CREATE TABLE IF NOT EXISTS app_user (
    -- ID : Clé primaire, auto-incrémentée (BIGSERIAL pour des raisons de performance/taille)
                                        id BIGSERIAL PRIMARY KEY,

                                        keycloak_id TEXT UNIQUE NOT NULL,

    profile_pic_url TEXT,

    -- Date d'adhésion
    joined_date TIMESTAMP WITHOUT TIME ZONE NOT NULL
    );

-- TABLE DE LIAISON : user_friends
CREATE TABLE IF NOT EXISTS user_friends (
                                            id BIGSERIAL PRIMARY KEY,

    -- Clé étrangère vers l'utilisateur (le demandeur d'amitié/l'un des deux côtés)
                                            user_id BIGINT NOT NULL,

    -- Clé étrangère vers l'ami
                                            friend_id BIGINT NOT NULL,

    -- Contrainte d'unicité : Un utilisateur ne peut être ami qu'une seule fois avec le même ami.
    -- (Cette contrainte est nécessaire même si l'amitié est stockée dans les deux sens)
                                            UNIQUE (user_id, friend_id),

    -- Contrainte : L'utilisateur ne peut pas être ami avec lui-même
    CHECK (user_id != friend_id),

    -- DÉFINITION DES CLÉS ÉTRANGÈRES :
    -- Clé étrangère vers la table app_user
    CONSTRAINT fk_user
    FOREIGN KEY (user_id)
    REFERENCES app_user(id)
    ON DELETE CASCADE, -- Supprime les amitiés si l'utilisateur est supprimé

-- Clé étrangère vers la table app_user pour l'ami
    CONSTRAINT fk_friend
    FOREIGN KEY (friend_id)
    REFERENCES app_user(id)
    ON DELETE CASCADE -- Supprime les amitiés si l'ami est supprimé
    );