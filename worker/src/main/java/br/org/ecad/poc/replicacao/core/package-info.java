/**
 * Replication core: coalescing, version guard, UUID assignment and SQL generation from table
 * specs. Knows nothing about Oracle or JDBC; the {@code oracle} package adapts these ports.
 * Populated from V-03 on; EN-01 only establishes the boundary.
 */
package br.org.ecad.poc.replicacao.core;
