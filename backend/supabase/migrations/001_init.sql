-- Assistant IA inclusif (application) — schéma initial.
-- Aucune donnée personnelle : pas de numéro, pas de messages, pas de photos, pas d'audio.
-- Seuls des compteurs d'usage anonymisés (HMAC d'un identifiant d'appareil) et l'annuaire.

create table if not exists services (
  id text primary key default gen_random_uuid()::text,
  name text not null,
  category text not null,             -- urgence | handicap | association | social | sante ...
  description text,
  phone text,
  address text,
  opening_hours text,
  verified boolean not null default false,
  verified_at timestamptz,
  source text,
  created_at timestamptz not null default now()
);

create table if not exists usage_daily (
  day date not null,
  subject text not null,              -- HMAC de l'appareil (ou « sos:… » haché)
  request_count integer not null default 0,
  primary key (day, subject)
);

create table if not exists usage_global (
  day date primary key,
  request_count integer not null default 0
);

alter table services enable row level security;
alter table usage_daily enable row level security;
alter table usage_global enable row level security;
-- Aucune policy : seule la clé service_role (serveur) y accède.

create or replace function increment_usage(p_subject text, p_day date)
returns table (user_count integer, global_count integer)
language plpgsql
security definer
set search_path = public
as $$
declare u integer; g integer;
begin
  insert into usage_daily (day, subject, request_count) values (p_day, p_subject, 1)
    on conflict (day, subject) do update set request_count = usage_daily.request_count + 1
    returning request_count into u;
  insert into usage_global (day, request_count) values (p_day, 1)
    on conflict (day) do update set request_count = usage_global.request_count + 1
    returning request_count into g;
  return query select u, g;
end;
$$;

create or replace function cleanup_expired_data()
returns void
language sql
security definer
set search_path = public
as $$
  delete from usage_daily where day < current_date - 2;
  delete from usage_global where day < current_date - 90;
$$;

revoke all on function increment_usage(text, date) from public, anon, authenticated;
revoke all on function cleanup_expired_data() from public, anon, authenticated;

-- Annuaire : NON vérifié tant qu'un humain n'a pas confirmé le numéro auprès de l'organisme.
insert into services (id, name, category, description, phone, verified, source)
values ('anph', 'ANPH — Agence Nationale des Personnes Handicapées', 'handicap',
        'Orientation et accompagnement des personnes en situation de handicap.',
        '+253 21 33 25 00', false, 'À vérifier avant publication')
on conflict (id) do nothing;
