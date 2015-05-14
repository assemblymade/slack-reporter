CREATE TABLE webhooks (
       id             uuid DEFAULT uuid_generate_v4() PRIMARY KEY,
       created_at     timestamp    NOT NULL,
       channel        text         NOT NULL,
       token          text         NOT NULL
)
