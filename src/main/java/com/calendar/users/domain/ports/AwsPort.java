package com.calendar.users.domain.ports;

import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Mono;

public interface AwsPort {

    Mono<String> storeObject(FilePart filePart, String key);
}
