package com.calendar.users.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum BusinessErrorCode {

    USER_NOT_FOUND("USR_BUS_001", "L'utilisateur n'a pas été trouvé", HttpStatus.NOT_FOUND),
    USER_ALREADY_EXISTS("USR_BUS_002", "L'utilisateur existe déjà", HttpStatus.CONFLICT);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
