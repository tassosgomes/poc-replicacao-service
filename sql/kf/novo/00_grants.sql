-- Executar como SYS em FREEPDB1 (aplicar-esquema-novo.sh). fn_uuid_v7 usa DBMS_CRYPTO.
whenever sqlerror exit sql.sqlcode
grant execute on sys.dbms_crypto to replica;
exit
