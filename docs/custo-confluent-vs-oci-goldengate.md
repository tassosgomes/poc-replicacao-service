# Custo estimado: Confluent Cloud × OCI GoldenGate

**Data do levantamento:** 2026-11-05
**Status:** estimativa de ordem de grandeza para apoiar a decisão entre as alternativas A (CDC Confluent) e D (GoldenGate) de [`desenho-alternativas.excalidraw`](desenho-alternativas.excalidraw). Não substitui cotação.

Todos os valores estão em US$ por mês, considerando 730 horas, preço de tabela e região não especificada.

## 1. Volume usado no cálculo

Picos reais do inventário [`inventario-tabelas-consolidado-resolucao-duplicidade 1.pdf`](inventario-tabelas-consolidado-resolucao-duplicidade%201.pdf), somando as tabelas que têm volumetria medida. O pico por segundo é a média da hora de pico (máximo por hora ÷ 3.600).

| Tabela | Pico na hora de pico (inclusões + alterações) | Dia de pico (inclusões + alterações) |
|---|---|---|
| OBRA_MUSICAL | ~100/s (15,85 + 84,10) | ~825 mil (215.754 + 609.734) |
| OBRA_TITULAR | ~72/s (28,77 + 43,08) | ~1,51 milhão (272.539 + 1.239.180) |
| DUPLICIDADE_GRUPO + DUPLICIDADE_GRUPO_DTL | ~49/s | ~219 mil |
| IMP_CODIGO_DTL_PROCESSO | ~1/s | ~11 mil |
| **Total** | **~222/s** (se os picos coincidirem) | **~2,57 milhões/dia** |

Os cálculos usam dois cenários, ambos com cerca de 1 KB por evento (imagem antes e depois mais cabeçalho; o tamanho é suposição, a medir na PoC):

- **Real:** todos os dias iguais ao pior dia medido. Dá ~77 milhões de eventos e **~80 GB por mês**.
- **Alvo de projeto:** 1.000 ev/s contínuos, 24x7 (alvo do ADR). Dá **~2,6 TB por mês**. É um teto pessimista.

## 2. Confluent Cloud

| Item | Preço de tabela | Real | Alvo 1.000 ev/s |
|---|---|---|---|
| Cluster Standard, 1 eCKU (25 MB/s de entrada, sobra) | US$ 0,75/eCKU-h | 548 | 548 |
| Entrada + saída de dados | US$ 0,035–0,050/GB (calculado a 0,05) | ~8 | ~262 |
| Armazenamento (retenção de 7 dias) | US$ 0,08/GB-mês | ~2 | ~49 |
| Conector Oracle CDC Source: volume | US$ 0,025/GB | ~2 | ~66 |
| **Conector Oracle CDC Source: hora de task** | **sob cotação (conector premium)** | **?** | **?** |
| Schema Registry (Stream Governance Essentials) | sem custo base (cobra por schema acima de 100) | 0 | 0 |
| Suporte Business (necessário em produção) | maior entre US$ 1.000 ou 10% do uso | 1.000 | 1.000 |
| **Total sem a hora de task** | | **~1.560** | **~1.925** |

Pontos que alteram esse número:

- **Rede privada exige o plano Enterprise.** O cluster Standard só aceita conexão pela internet. Com PrivateLink, o cluster Enterprise custa US$ 1,75–2,25/eCKU-h, mais US$ 0,03 por task-hora de conector. O total sobe para **~US$ 2.300–3.000/mês**, ainda sem a hora de task.
- **O conector precisa alcançar o BDAD.** O conector gerenciado roda no Confluent Cloud e tem que acessar o BDAD on-prem. Rodar o conector on-prem evita isso, mas o conector premium passa a exigir licença do Confluent Platform.
- **Tópico de redo.** O conector Oracle CDC também grava um tópico com o redo, o que pode quase dobrar entrada e saída.
- **Consumer próprio.** O JDBC Sink não gera UUIDv7 nem aplica guarda de versão, então o consumer Java continua necessário, rodando em infraestrutura própria.
- **Cluster dedicado de Connect,** se usado, acrescenta US$ 0,27778/h (~US$ 203/mês).

## 3. OCI GoldenGate

Preço oficial obtido na API de preços da Oracle (`apexapps.oracle.com/pls/apex/cetools/api/v1/products`) em 2026-11-05:

| SKU | Produto | Métrica | Preço |
|---|---|---|---|
| B92992 | OCI GoldenGate (licença incluída) | OCPU por hora | US$ 1,3441 |
| B92993 | OCI GoldenGate – BYOL | OCPU por hora | US$ 0,3226 |

| Item | Real (1 OCPU) | Alvo (1–2 OCPU) |
|---|---|---|
| Licença incluída | **~981** | 981–1.962 |
| BYOL (com licença própria de GoldenGate) | **~236** | 236–471 |
| Rede | ~0 (os primeiros 10 TB/mês de saída na OCI são grátis; VPN site-to-site sem custo) | ~0 |
| Suporte | incluído na OCI | incluído |

Pontos que alteram esse número:

- **Dimensionamento é estimativa.** 1 OCPU deve bastar para ~222 ev/s, mas é estimativa, a confirmar com medição.
- **Cobrança só com a implantação ligada.** Parar ou encerrar a implantação interrompe a cobrança por OCPU.
- **Rede até a OCI.** O BDAD e o MAESTRO on-prem precisam se conectar à OCI por VPN ou FastConnect. A FastConnect tem custo de porta próprio, não incluído aqui.
- **Sem código próprio.** A replicação não exige código; o UUIDv7 sai por trigger no MAESTRO.

## 4. Comparação

| | Confluent Cloud | OCI GoldenGate |
|---|---|---|
| Custo mensal, cenário real | ~US$ 1.560 (Standard) a ~US$ 2.700 (Enterprise) **+ hora de task sob cotação** | ~US$ 981 (licença incluída) ou ~US$ 236 (BYOL) |
| Custo mensal, alvo 1.000 ev/s | ~US$ 1.925 a ~US$ 3.000 **+ hora de task** | US$ 981–1.962 ou US$ 236–471 |
| Componentes próprios | consumer Java (UUIDv7 e guarda de versão) | trigger de UUIDv7 no destino |
| Operação | Kafka gerenciado + conector + consumer | serviço gerenciado |
| Consistência | ordem só por chave; transação multi-tabela chega em tópicos separados | transação inteira, na ordem de commit |

**Leitura:** com o volume atual, o OCI GoldenGate custa menos e tem menos partes a construir e operar.

**Possível licença existente:** os DDLs reais em [`docs/DDL/`](DDL/) mostram supplemental logging ALL e grupos `GGS_*` em OBRA_MUSICAL, OBRA_TITULAR e TITULARIDADE, o que indica GoldenGate já em uso no BDAD. Se existir licença on-prem, o BYOL reduz o custo para ~US$ 236 por OCPU/mês. Isso precisa ser confirmado com o DBA (ver Q-G1 e Q-G2 em [`tasks/prd-poc-replicacao-goldengate/prd.draft.md`](../tasks/prd-poc-replicacao-goldengate/prd.draft.md)).

## 5. Pendências

| # | Pendência | Com quem | Impacto |
|---|---|---|---|
| P1 | Cotação da hora de task do Oracle CDC Source ou do XStream CDC Source (premium) | Confluent | Total do Confluent incompleto sem ela |
| P2 | Existe licença de GoldenGate on-prem (grupos `GGS_*` no BDAD)? | DBA do BDAD | Define licença incluída ou BYOL |
| P3 | Tamanho médio real do evento | PoC GoldenGate (G10) | Corrige os volumes em GB |
| P4 | Conectividade BDAD/MAESTRO até a nuvem (VPN, FastConnect, PrivateLink) | Infraestrutura | Pode exigir o plano Enterprise no Confluent e custo de porta na OCI |
| P5 | Confirmar o mínimo de US$ 1.000 do suporte Business no Confluent | Confluent | A página consultada tinha layout quebrado |

## 6. Confiabilidade dos números

- **Oficial:** preço do OCI GoldenGate (API de preços da Oracle) e preços de cluster e conectores do Confluent (páginas de preço da Confluent).
- **Fonte não confirmada:** mínimo do suporte Business (página com layout quebrado) e Stream Governance Essentials (documentação; uma fonte antiga diverge na quantidade de schemas grátis).
- **Suposição:** 1 KB por evento, todos os dias iguais ao pior dia, 1 OCPU suficiente para o volume real, retenção de 7 dias.
- As faixas por GB do Confluent e os preços da OCI podem variar por região e por contrato.

## Fontes

- [Oracle Cloud Price List](https://www.oracle.com/cloud/price-list/) (SKUs B92992/B92993 consultados na API de preços da Oracle)
- [Oracle GoldenGate Licensing in Cloud and Hybrid Environments (Redress Compliance)](https://redresscompliance.com/cloud-based-goldengate-licensing-oci-aws-and-azure/)
- [Confluent Pricing](https://www.confluent.io/pricing/)
- [Confluent Managed Connector Pricing](https://www.confluent.io/confluent-cloud/connect-pricing/)
- [Confluent Connector Billing (docs)](https://docs.confluent.io/cloud/current/billing/dimensions/connect.html)
- [Confluent Billing Dimensions (docs)](https://docs.confluent.io/cloud/current/billing/billing-dimensions.html)
- [Oracle CDC Source Connector (Confluent Hub)](https://www.confluent.io/hub/confluentinc/kafka-connect-oracle-cdc)
- [Stream Governance Billing (docs)](https://docs.confluent.io/cloud/current/billing/dimensions/stream-governance.html)
- [Confluent Cloud Support](https://www.confluent.io/pt-br/confluent-cloud/support/)
- [Confluent Support for Confluent Cloud (docs)](https://docs.confluent.io:443/cloud/current/support.html)
- [Kafka Cost Comparison 2026 (AxonOps)](https://axonops.com/blog/kafka-cost-comparison-2026-self-hosted-vs-amazon-msk-vs-confluent-cloud/)
- [Confluent Cloud Pricing guide (CloudZero)](https://www.cloudzero.com/blog/confluent-cloud-pricing/)
