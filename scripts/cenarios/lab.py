"""Alvo do lab (containers, hosts, portas, usuarios; senha = usuario como no lab): unico lugar dos padroes em Python.

Sem variaveis de ambiente, os valores sao os do lab Java (poc-legado/poc-novo). Para mirar outro
lab (ex.: GoldenGate), exporte as variaveis LAB_* (veja scripts/lab-gg.env).
"""
import os


def _env(chave, padrao):
    return os.environ.get(chave) or padrao


HOST_LEGADO = _env("LAB_HOST_LEGADO", "desenv-server")
HOST_NOVO = _env("LAB_HOST_NOVO", "infra-server")
CONTAINER_LEGADO = _env("LAB_CONTAINER_LEGADO", "poc-legado")
CONTAINER_NOVO = _env("LAB_CONTAINER_NOVO", "poc-novo")
USUARIO_LEGADO = _env("LAB_USUARIO_LEGADO", "bdad")
USUARIO_NOVO = _env("LAB_USUARIO_NOVO", "replica")
# Porta publicada no host (acesso de outro host); a interna e a do listener dentro do container.
PORTA_LEGADO = _env("LAB_PORTA_LEGADO", "1521")
PORTA_NOVO = _env("LAB_PORTA_NOVO", "1522")
PORTA_INTERNA = _env("LAB_PORTA_INTERNA", "1521")
SERVICO = _env("LAB_SERVICO", "FREEPDB1")
REDE = _env("LAB_REDE", "poc-rede")
PREFIXO = _env("LAB_PREFIXO", "poc-")

HOSTS = (HOST_LEGADO, HOST_NOVO)
LEGADO = (HOST_LEGADO, CONTAINER_LEGADO, USUARIO_LEGADO)
NOVO = (HOST_NOVO, CONTAINER_NOVO, USUARIO_NOVO)
