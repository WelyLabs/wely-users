package com.calendar.users.application.dtos;

/** A signed URL for the picture just uploaded, valid for an hour; null when it could not be signed. */
public record PictureResponse(String profilePicUrl) {
}
