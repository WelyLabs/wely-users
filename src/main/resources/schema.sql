DROP SCHEMA public CASCADE;
CREATE SCHEMA public;
GRANT ALL ON SCHEMA public TO CURRENT_USER;
GRANT ALL ON SCHEMA public TO PUBLIC;

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