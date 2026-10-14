-- Baseline schema for calendar-users-api.
--
-- This file lives outside src/main/resources on purpose: anything on the
-- classpath risks being picked up by Spring's SQL init and run against a live
-- database. Apply it deliberately, or hand it to Flyway/Liquibase.

CREATE TABLE IF NOT EXISTS app_user (
                                        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                        keycloak_id TEXT UNIQUE NOT NULL,
                                        user_name TEXT NOT NULL,
                                        hashtag INTEGER NOT NULL,
                                        first_name TEXT,
                                        last_name TEXT,
                                        profile_pic_url TEXT,
                                        joined_date TIMESTAMP WITHOUT TIME ZONE NOT NULL,

                                        CONSTRAINT unique_user_identity UNIQUE (user_name, hashtag)
);