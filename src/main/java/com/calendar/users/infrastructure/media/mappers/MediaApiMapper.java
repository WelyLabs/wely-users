package com.calendar.users.infrastructure.media.mappers;

import com.calendar.users.domain.models.StoredMedia;
import com.calendar.users.infrastructure.media.models.StoreMediaResponse;
import org.mapstruct.Mapper;

/** Translates wely-media's payloads into the domain's models. */
@Mapper(componentModel = "spring")
public interface MediaApiMapper {

    StoredMedia toStoredMedia(StoreMediaResponse response);
}
