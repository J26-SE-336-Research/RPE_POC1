-- RPE policy store (C3). PostgreSQL 16. Contract A3.4.
-- Governance (C3 FR10): a policy row cannot exist without a human approval record, and specs are
-- append-only (a change is a new version, never an in-place update).

create schema if not exists policy;

create table if not exists policy.policies (
    policy_id         text        not null,
    version           int         not null check (version > 0),
    epoch             text        not null unique,
    scope             jsonb       not null,
    spec              jsonb       not null,
    source            text        not null check (source in ('c4-recommendation', 'admin')),
    recommendation_id text,
    approved_by       text        not null check (length(approved_by) > 0),
    approved_at       timestamptz not null,
    status            text        not null default 'approved-pending-rollout',
    created_at        timestamptz not null default now(),
    primary key (policy_id, version)
);

create table if not exists policy.rollouts (
    rollout_id     bigserial primary key,
    policy_id      text not null,
    version        int  not null,
    mode           text not null check (mode in ('two-phase', 'ordered', 'naive')),
    distribution   text not null default 'push' check (distribution in ('push', 'configmap', 'poll')),
    canary_percent int  not null default 100 check (canary_percent between 1 and 100),
    state          text not null default 'pending',
    started_at     timestamptz,
    finished_at    timestamptz,
    halted_reason  text,
    foreign key (policy_id, version) references policy.policies (policy_id, version)
);

create table if not exists policy.deliveries (
    pod      text not null,
    service  text not null,
    epoch    text not null,
    version  int  not null,
    hash     text not null,
    state    text not null check (state in ('staged', 'active', 'retired', 'failed')),
    acked_at timestamptz not null default now(),
    error    text,
    primary key (pod, epoch)
);

