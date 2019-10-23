#!/usr/bin/env bash

docker exec -i --user postgres "$1" createdb -p "$2" transactiondb

docker exec -i --user postgres "$1" psql -p "$2" transactiondb -a <<__END
create user micronaut_ge password 'kafka-graphql-pw';
__END

docker exec -i "$1" psql -Umicronaut_ge -p "$2" transactiondb -a <<__END
drop table if exists transaction;
drop table if exists account;

create table transaction(
    id int generated by default as identity primary key,
    iban text not null,
    new_balance text not null,
    changed_by text not null,
    from_to text not null,
    direction text not null,
    descr text not null);

create index transaction_iban on transaction using btree (iban);

create table account(
    username text not null primary key,
    password text not null,
    uuid UUID not null);

create index account_uuid on account using btree (uuid);
__END
