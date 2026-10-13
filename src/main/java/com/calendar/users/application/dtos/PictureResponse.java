package com.calendar.users.application.dtos;

/** A signed URL for the picture just uploaded, valid for an hour. */
public record PictureResponse(String profilePicUrl) {
}
