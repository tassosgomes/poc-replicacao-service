#!/usr/bin/env bash
# Cria (via REST) credenciais, trandata, checkpoint/heartbeat, Extract EXTBDAD e Replicat REPBDAD no gg-hub.
# Uso: gg/criar-pipeline.sh <SCN da carga inicial>   (o Replicat inicia com AFTERCSN <SCN>)
set -euo pipefail
cd "$(dirname "$0")"; source ./lib.sh
SCN=${1:?SCN da carga inicial}
J() { jq -n "$@"; }
ORIG=OracleGoldenGate.ggorigem; DEST=OracleGoldenGate.ggdestino
gg POST /credentials/OracleGoldenGate/ggorigem  '{"userid":"ggadmin@192.168.0.5:1523/FREEPDB1","password":"ggadmin"}' | ggmsg
gg POST /credentials/OracleGoldenGate/ggdestino '{"userid":"ggadmin@192.168.0.11:1524/FREEPDB1","password":"ggadmin"}' | ggmsg
# ADD SCHEMATRANDATA bdad ALLCOLS (before-image completa: necessario para CDR/COMPARECOLS e para o MAP).
gg POST /connections/$ORIG/trandata/schema '{"operation":"add","schemaName":"bdad","allColumns":true}' | ggmsg
gg POST /connections/$DEST/tables/checkpoint '{"operation":"add","name":"ggadmin.ggschkpt"}' | ggmsg
for c in $DEST $ORIG; do gg POST /connections/$c/tables/heartbeat '{"frequency":10,"purgeFrequency":1,"retentionTime":1}' | ggmsg; done
# Extract integrado (conexao no PDB). Sem Distribution Path: hub unico, o Replicat le o trail local.
ext=$(J --argjson c "$(prm_json params/EXTBDAD.prm)" '{description:"Extract integrado bdad",source:"tranlogs",begin:"now",config:$c,credentials:{domain:"OracleGoldenGate",alias:"ggorigem"},registration:{optimized:false},targets:[{name:"ea",sizeMB:100}]}')
gg POST /extracts/EXTBDAD "$ext" | ggmsg
gg POST /commands/execute '{"name":"start","processName":"EXTBDAD"}' | ggmsg
# Replicat classico (params/REPBDAD.prm, com CDR USEMAX) lendo o trail ea; checkpoint no destino. Inicia depois do SCN da carga.
rep=$(J --argjson c "$(prm_json params/REPBDAD.prm)" '{description:"Replicat classico bdad",config:$c,source:{name:"ea"},credentials:{domain:"OracleGoldenGate",alias:"ggdestino"},mode:{type:"nonintegrated"},checkpoint:{table:"ggadmin.ggschkpt"},begin:"now"}')
gg POST /replicats/REPBDAD "$rep" | ggmsg
# START ... AFTERCSN: a REST (/commands/execute) rejeitou todas as formas testadas; usa-se o adminclient.
./adminclient.sh "START REPLICAT REPBDAD AFTERCSN $SCN"
