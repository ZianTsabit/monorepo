-- The schema under its own names.
--
-- V1-V3 built (or adopted) tables named after the framework this API was first
-- written in. This migration renames every table, rebuilds every secondary
-- constraint and index under a name that says what it is, makes the foreign
-- keys cascade, and gives the timestamp and counter columns database defaults.
-- After it, nothing in the schema refers to anything outside this application.
--
-- The constraint and index names it replaces are found through the catalogue
-- and dropped by whatever name they have, rather than named here: a database
-- that existed before Flyway did is not guaranteed to have the names V1 would
-- have given them, and this way an extra or differently named one cannot
-- survive the rename or break it.
-- Primary keys are kept (the foreign keys point at them) and only renamed.

-- 1. Tables nothing here has ever read: the previous framework's sessions,
--    migration log, admin log, permissions and groups. IF EXISTS, because only
--    a database that predates V1 has them.
DROP TABLE IF EXISTS
    django_admin_log,
    django_session,
    django_migrations,
    django_content_type,
    auth_user_user_permissions,
    auth_user_groups,
    auth_group_permissions,
    auth_group,
    auth_permission
CASCADE;

-- 2. Table names.
ALTER TABLE myapp_post        RENAME TO post;
ALTER TABLE myapp_postviewday RENAME TO post_view_day;
ALTER TABLE myapp_book        RENAME TO book;
ALTER TABLE myapp_comment     RENAME TO comment;
ALTER TABLE myapp_reaction    RENAME TO reaction;
ALTER TABLE myapp_pagecontent RENAME TO page_content;
ALTER TABLE auth_user         RENAME TO app_user;

-- 3. Clear every foreign key, unique and check constraint and secondary index,
--    and give the primary keys and identity sequences their default names.
DO $$
DECLARE
    tables constant text[] := ARRAY['post', 'post_view_day', 'book', 'comment', 'reaction', 'page_content',
                                    'app_user'];
    r record;
BEGIN
    -- Foreign keys first: the unique constraints below cannot go while one
    -- depends on them (none should, but the order costs nothing).
    FOR r IN SELECT conrelid::regclass::text AS tbl, conname
               FROM pg_constraint
              WHERE contype = 'f' AND conrelid::regclass::text = ANY (tables) LOOP
        EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', r.tbl, r.conname);
    END LOOP;

    FOR r IN SELECT conrelid::regclass::text AS tbl, conname
               FROM pg_constraint
              WHERE contype IN ('u', 'c') AND conrelid::regclass::text = ANY (tables) LOOP
        EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', r.tbl, r.conname);
    END LOOP;

    -- Whatever indexes remain besides the primary keys: plain, GIN, and the
    -- pattern-ops copies of unique columns that a pre-V1 database also has.
    FOR r IN SELECT indexrelid::regclass::text AS idx
               FROM pg_index
              WHERE NOT indisprimary AND indrelid::regclass::text = ANY (tables) LOOP
        EXECUTE format('DROP INDEX %I', r.idx);
    END LOOP;

    FOR r IN SELECT conrelid::regclass::text AS tbl, conname
               FROM pg_constraint
              WHERE contype = 'p' AND conrelid::regclass::text = ANY (tables) LOOP
        IF r.conname <> r.tbl || '_pkey' THEN
            EXECUTE format('ALTER TABLE %I RENAME CONSTRAINT %I TO %I', r.tbl, r.conname, r.tbl || '_pkey');
        END IF;
    END LOOP;

    FOR r IN SELECT t AS tbl, pg_get_serial_sequence(t, 'id') AS seq FROM unnest(tables) AS t LOOP
        IF r.seq IS NOT NULL AND r.seq <> 'public.' || r.tbl || '_id_seq' THEN
            EXECUTE format('ALTER SEQUENCE %s RENAME TO %I', r.seq, r.tbl || '_id_seq');
        END IF;
    END LOOP;
END
$$;

-- 4. The owner's account: only what this application reads. The existing hash
--    is tagged so Spring Security's DelegatingPasswordEncoder routes it to the
--    legacy matcher, which verifies it once; a successful login then stores it
--    again as bcrypt. A hash starting '!' was never usable and stays unusable.
ALTER TABLE app_user
    DROP COLUMN first_name,
    DROP COLUMN last_name,
    DROP COLUMN email,
    DROP COLUMN is_staff,
    DROP COLUMN is_superuser;
ALTER TABLE app_user RENAME COLUMN is_active TO enabled;
ALTER TABLE app_user RENAME COLUMN date_joined TO created_at;
ALTER TABLE app_user ALTER COLUMN password TYPE varchar(255);
UPDATE app_user SET password = '{pbkdf2-legacy}' || password
 WHERE password NOT LIKE '{%' AND password NOT LIKE '!%';

-- 5. Constraints and indexes, under names that say what they are.
ALTER TABLE app_user     ADD CONSTRAINT app_user_username_key UNIQUE (username);
ALTER TABLE page_content ADD CONSTRAINT page_content_key_key  UNIQUE (key);

ALTER TABLE post
    ADD CONSTRAINT post_slug_key UNIQUE (slug),
    ADD CONSTRAINT post_view_count_check CHECK (view_count >= 0);
-- GIN, because browsing by tag is a containment test over an array.
CREATE INDEX post_tags_gin ON post USING gin (tags);
CREATE INDEX post_status_idx ON post (status);

ALTER TABLE book
    ADD CONSTRAINT book_slug_key UNIQUE (slug),
    ADD CONSTRAINT book_release_year_check CHECK (release_year >= 0);
CREATE INDEX book_genres_gin ON book USING gin (genres);
CREATE INDEX book_status_idx ON book (status);

-- A post's children go with it: deleting a post is one statement.
ALTER TABLE post_view_day
    ADD CONSTRAINT post_view_day_post_fkey FOREIGN KEY (post_id) REFERENCES post (id) ON DELETE CASCADE,
    -- One row per post per day, which is what makes recording a view one upsert.
    ADD CONSTRAINT post_view_day_post_date_key UNIQUE (post_id, date),
    ADD CONSTRAINT post_view_day_count_check CHECK (count >= 0);
CREATE INDEX post_view_day_date_idx ON post_view_day (date);

ALTER TABLE comment
    ADD CONSTRAINT comment_post_fkey FOREIGN KEY (post_id) REFERENCES post (id) ON DELETE CASCADE;
-- The one query the public page makes: this post's visible comments, in order.
CREATE INDEX comment_post_status_created_idx ON comment (post_id, status, created_at);

ALTER TABLE reaction
    ADD CONSTRAINT reaction_post_fkey FOREIGN KEY (post_id) REFERENCES post (id) ON DELETE CASCADE,
    -- What makes the toggle a delete-or-insert rather than a read-then-write.
    -- Its index also serves the per-post, per-emoji count.
    ADD CONSTRAINT reaction_post_emoji_visitor_key UNIQUE (post_id, emoji, visitor);

-- 6. Defaults, so a row can be written without restating the obvious.
ALTER TABLE post
    ALTER COLUMN view_count SET DEFAULT 0,
    ALTER COLUMN created_at SET DEFAULT now(),
    ALTER COLUMN updated_at SET DEFAULT now();
ALTER TABLE post_view_day ALTER COLUMN count SET DEFAULT 0;
ALTER TABLE book
    ALTER COLUMN created_at SET DEFAULT now(),
    ALTER COLUMN updated_at SET DEFAULT now();
ALTER TABLE comment
    ALTER COLUMN created_at SET DEFAULT now(),
    ALTER COLUMN updated_at SET DEFAULT now();
ALTER TABLE reaction ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE page_content ALTER COLUMN updated_at SET DEFAULT now();
ALTER TABLE app_user
    ALTER COLUMN enabled SET DEFAULT true,
    ALTER COLUMN created_at SET DEFAULT now();
