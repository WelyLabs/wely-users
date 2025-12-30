package com.calendar.users.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum TechnicalErrorCode {

    KEYCLOAK_ERROR("USR_TEC_001", "Erreur lors de la communication avec Keycloak"),
    DATABASE_ERROR("USR_TEC_002", "Erreur lors de la communication avec la base de données"),
    KAFKA_ERROR("USR_TEC_003", "Erreur lors de la communication avec Kafka");

    private final String code;
    private final String message;
}
