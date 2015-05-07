CREATE TABLE messages (
       id             uuid DEFAULT uuid_generate_v4() PRIMARY KEY,
       created_at     timestamp    NOT NULL,
       channel        text         NOT NULL,
       message        json         NOT NULL
)
