-- Runtime state consumed and written by the RPE Kubernetes Operator.
-- Keep PostgreSQL as the source of truth; LISTEN/NOTIFY is only a wake-up signal.

-- Proxy acknowledgements retain the Operator's expected hash separately from the proxy's confirmation.
alter table policy.deliveries
    add column if not exists confirmed_state text,
    add column if not exists confirmed_hash text,
    add column if not exists confirmed_at timestamptz;

-- Measurements recorded after a rollout completes.
alter table policy.rollouts
    add column if not exists skew_ms integer,
    add column if not exists mixed_policy_requests bigint not null default 0,
    add column if not exists convergence_ms bigint not null default 0,
    add column if not exists drain_ms bigint not null default 0;

-- The API only creates these two rollout modes. Replace the original broader constraint
-- while supporting databases where 001_init.sql has already been applied.
update policy.rollouts set mode = 'two-phase' where mode = 'ordered';
alter table policy.rollouts drop constraint if exists rollouts_mode_check;
alter table policy.rollouts
    add constraint rollouts_mode_check check (mode in ('two-phase', 'naive'));

-- Durable approval events let the operator catch up after downtime; NOTIFY merely wakes it promptly.
create table if not exists policy.policy_events (
    id         bigserial primary key,
    policy_id  text        not null,
    version    integer     not null,
    kind       text        not null,
    payload    jsonb       not null,
    created_at timestamptz not null default now(),
    foreign key (policy_id, version)
        references policy.policies (policy_id, version)
);

create index if not exists policy_events_kind_id_idx
    on policy.policy_events (kind, id);

create or replace function policy.notify_policy_approved()
returns trigger
language plpgsql
as $$
declare
    event_payload jsonb;
begin
    event_payload := jsonb_build_object(
        'policyId', new.policy_id,
        'version', new.version,
        'epoch', new.epoch
    );

    insert into policy.policy_events (policy_id, version, kind, payload)
    values (new.policy_id, new.version, 'approved', event_payload);

    perform pg_notify('rpe_policy_approved', event_payload::text);
    return new;
end;
$$;

drop trigger if exists policy_approved_event on policy.policies;
create trigger policy_approved_event
    after insert on policy.policies
    for each row execute function policy.notify_policy_approved();
