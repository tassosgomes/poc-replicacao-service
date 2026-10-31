#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/comum.sh"
arquivo=${1:?informe o caminho do CSV}
duracao=${2:-60}
[[ $duracao =~ ^[1-9][0-9]*$ ]] || { printf 'duração inválida\n' >&2; exit 2; }
mkdir -p "$(dirname "$arquivo")"
printf 'ts,container,cpu_percent,memoria,sessoes_ativas\n' > "$arquivo"
coletar() {
  local host=$1 container=$2 stats sessoes
  stats=$(ssh_poc "$host" "docker stats --no-stream --format '{{.CPUPerc}};{{.MemUsage}}' $container")
  sessoes=$(ssh_poc "$host" "docker exec -i $container sqlplus -s '/ as sysdba'" <<'SQL'
set heading off feedback off pagesize 0 verify off
select count(*) from v$session where status='ACTIVE' and type='USER';
exit
SQL
  )
  sessoes=$(printf '%s\n' "$sessoes" | tr -d '[:space:]')
  [[ $sessoes =~ ^[0-9]+$ && $stats == *';'* ]] || { printf 'falha ao coletar %s\n' "$container" >&2; return 1; }
  printf '%s,%s,%s,%s\n' "$(date -u +%FT%TZ)" "$container" "${stats/;/,}" "$sessoes" >> "$arquivo"
}
fim=$((SECONDS + duracao))
while (( SECONDS < fim )); do
  coletar desenv-server poc-legado
  coletar infra-server poc-novo
  (( SECONDS >= fim )) || sleep 5
done
