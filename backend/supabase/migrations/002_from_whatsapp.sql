-- À exécuter UNIQUEMENT si vous aviez déjà installé la version WhatsApp (ancien schéma).
-- Supprime les tables et fonctions qui contenaient des données de conversation.
drop function if exists claim_job(integer) cascade;
drop function if exists delete_user_data(text) cascade;
drop table if exists jobs cascade;
drop table if exists sessions cascade;
drop table if exists user_preferences cascade;

drop function if exists increment_usage(text, date) cascade;
drop function if exists cleanup_expired_data() cascade;
drop table if exists usage_daily cascade;
drop table if exists usage_global cascade;
-- Puis réexécutez 001_init.sql (les tables « services » existantes sont conservées).
