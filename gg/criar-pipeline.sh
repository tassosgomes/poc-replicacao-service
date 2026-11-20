#!/usr/bin/env bash
# Cria (via REST) credenciais, trandata, checkpoint/heartbeat, Extract EXTBDAD e Replicat REPBDAD no gg-hub.
# Uso: gg/criar-pipeline.sh base            credenciais, trandata, checkpoint/heartbeat, Extract (iniciado) e Replicat (criado, parado)
#      gg/criar-pipeline.sh replicat <SCN>  START REPLICAT ... AFTERCSN <SCN> e autostart/autorestart do Replicat
#      gg/criar-pipeline.sh <SCN>           as duas etapas em sequencia (sem carga inicial entre elas: so para testes)
# Entre "base" e "replicat" roda a carga inicial por SCN (scripts/gg/carga-inicial.sh), com o Extract ja ativo.
set -euo pipefail
cd "$(dirname "$0")"; source ./lib.sh
ETAPA=${1:?base | replicat <SCN> | <SCN>}
SCN=
case $ETAPA in
  base) ;;
  replicat) SCN=${2:?SCN da carga inicial} ;;
  *[!0-9]*) echo "etapa invalida: $ETAPA" >&2; exit 2 ;;
  *) SCN=$ETAPA; ETAPA=tudo ;;
esac
# Autostart/autorestart (Admin Service, managedProcessSettings): o processo sobe quando o Admin Service sobe
# (docker start/restart do hub) e e reiniciado se abendar (5 s de espera, 9 tentativas por 60 s, sem desabilitar).
MANAGED='{"managedProcessSettings":{"autoStart":{"enabled":true,"delay":0},"autoRestart":{"enabled":true,"onSuccess":false,"delay":5,"retries":9,"window":60,"disableOnFailure":false}}}'
J() { jq -n "$@"; }
ORIG=OracleGoldenGate.ggorigem; DEST=OracleGoldenGate.ggdestino
if [[ $ETAPA == base || $ETAPA == tudo ]]; then
gg POST /credentials/OracleGoldenGate/ggorigem  '{"userid":"ggadmin@192.168.0.5:1523/FREEPDB1","password":"ggadmin"}' | ggmsg
gg POST /credentials/OracleGoldenGate/ggdestino '{"userid":"ggadmin@192.168.0.11:1524/FREEPDB1","password":"ggadmin"}' | ggmsg
# ADD SCHEMATRANDATA bdad ALLCOLS (before-image completa: necessario para CDR/COMPARECOLS e para o MAP).
gg POST /connections/$ORIG/trandata/schema '{"operation":"add","schemaName":"bdad","allColumns":true}' | ggmsg
gg POST /connections/$DEST/tables/checkpoint '{"operation":"add","name":"ggadmin.ggschkpt"}' | ggmsg
for c in $DEST $ORIG; do gg POST /connections/$c/tables/heartbeat '{"frequency":10,"purgeFrequency":1,"retentionTime":1}' | ggmsg; done
# Extract integrado (conexao no PDB). Sem Distribution Path: hub unico, o Replicat le o trail local.
ext=$(J --argjson m "$(jq .managedProcessSettings <<< "$MANAGED")" --argjson c "$(prm_json params/EXTBDAD.prm)" '{description:"Extract integrado bdad",source:"tranlogs",begin:"now",config:$c,credentials:{domain:"OracleGoldenGate",alias:"ggorigem"},registration:{optimized:false},managedProcessSettings:$m,targets:[{name:"ea",sizeMB:100}]}')
gg POST /extracts/EXTBDAD "$ext" | ggmsg
gg POST /commands/execute '{"name":"start","processName":"EXTBDAD"}' | ggmsg
# Replicat classico (params/REPBDAD.prm, com CDR USEMAX) lendo o trail ea; checkpoint no destino. Inicia depois do SCN da carga.
rep=$(J --argjson c "$(prm_json params/REPBDAD.prm)" '{description:"Replicat classico bdad",config:$c,source:{name:"ea"},credentials:{domain:"OracleGoldenGate",alias:"ggdestino"},mode:{type:"nonintegrated"},checkpoint:{table:"ggadmin.ggschkpt"},begin:"now"}')
gg POST /replicats/REPBDAD "$rep" | ggmsg
fi
if [[ $ETAPA == replicat || $ETAPA == tudo ]]; then
# START ... AFTERCSN: a REST (/commands/execute) rejeitou todas as formas testadas; usa-se o adminclient.
./adminclient.sh "START REPLICAT REPBDAD AFTERCSN $SCN"
# Autostart so depois do primeiro START (com AFTERCSN): um autostart antes dele iniciaria o Replicat sem o SCN.
gg PATCH /replicats/REPBDAD "$MANAGED" | ggmsg
fi
