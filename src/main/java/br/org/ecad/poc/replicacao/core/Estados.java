package br.org.ecad.poc.replicacao.core;

import java.util.Map;
import java.util.Set;

/** Result of a state lookup: rows found by key, plus the keys that do not exist in the origin. */
public record Estados(Map<Long, Linha> encontradas, Set<Long> ausentes) {}
