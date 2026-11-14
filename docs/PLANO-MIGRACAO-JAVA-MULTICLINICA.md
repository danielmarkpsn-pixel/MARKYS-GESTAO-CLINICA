# MARKYS Gestão Clínica — Auditoria e plano de evolução

**Branch de trabalho:** `migration/java-springboot-foundation`  
**Branch preservada:** `main`  
**Escopo desta entrega:** auditoria inicial baseada nos arquivos atualmente disponíveis na raiz e plano de migração. Nenhuma tela ou fluxo existente foi removido ou reescrito.

## 1. Estado atual observado

O repositório contém uma aplicação web estática servida por um servidor HTTP escrito em Windows PowerShell.

### Fluxos e telas identificados
- Recepção e cadastro/gerenciamento da fila: `index.html`.
- Painel profissional: `consultorio.html`.
- Triagem e sinais vitais: `preparo.html`, servida também em `/triagem`.
- Atendimento clínico e prontuário: `atendimento.html`.
- Sala de espera pública: modo de painel da recepção (`/?painel=1`).
- Cadastro local de profissionais, apresentação de mídia e fila compartilhada.

### Persistência atual
O servidor inicializa e utiliza arquivos JSON locais:
- `fila.json`
- `profissionais.json`
- `prontuarios.json`
- `midia.json`
- diretório `midia-sala/`

As rotas observadas incluem leitura e gravação em `/api/state`, `/api/professionals`, `/api/records`, `/api/media` e upload/listagem de mídia. A gravação de fila e prontuários recebe arrays JSON completos e substitui o arquivo correspondente.

## 2. Riscos técnicos identificados

| Prioridade | Constatação | Consequência |
|---|---|---|
| Crítica | README informa que não há login, criptografia nem auditoria; APIs de dados observadas não verificam identidade | Dados e operações ficam expostos a qualquer dispositivo que alcance o servidor |
| Crítica | Dados clínicos e fila ficam em arquivos JSON sem criptografia | Risco de exposição, corrupção e perda de histórico |
| Alta | Atualizações enviam o array inteiro para substituição do arquivo | Escritas simultâneas podem sobrescrever alterações de outro usuário |
| Alta | Servidor usa `TcpListener.AcceptTcpClient()` em loop sequencial | Uma requisição lenta pode atrasar as demais; não é base adequada para várias clínicas |
| Alta | PowerShell e caminhos locais são a infraestrutura de execução | Acoplamento ao Windows e dificuldade de operar com confiabilidade em VPS Linux |
| Alta | Não há autenticação, autorização por perfil ou isolamento por clínica no backend observado | Não atende operação multi-clínica nem o princípio de menor privilégio |
| Alta | Não há transações entre fila, triagem e prontuário | Pode haver inconsistência entre etapas do atendimento |
| Média | README descreve limite de 10 consultórios, enquanto a interface aparenta permitir configuração distinta | Documentação e comportamento precisam ser comparados em teste funcional |
| Média | O README documenta atualização automática; a triagem consultada usa polling de 2 segundos | Tráfego repetido e experiência variável; deve ser substituído por atualização eficiente após medir uso |

**Segurança:** o próprio README orienta não inserir dados reais de pacientes até que sejam implementadas proteções. Essa orientação deve ser mantida até a conclusão da migração, validação de segurança e homologação.

## 3. Arquitetura de destino

### Stack
- Java 21 LTS + Spring Boot 3.x.
- Spring Web para API REST.
- Spring Security para autenticação e autorização.
- Spring Data JPA + Flyway para persistência e evolução do esquema.
- PostgreSQL como banco transacional.
- Frontend atual em HTML/CSS/JavaScript preservado inicialmente, consumindo a API Java.
- Docker Compose para aplicação e banco em ambiente de desenvolvimento/homologação; VPS Linux para produção.
- HTTPS por proxy reverso (Caddy ou Nginx), backups criptografados e monitoramento.

A interface não precisa ser reescrita em Java. A migração será do servidor, regras de negócio e persistência, mantendo a experiência visual e os fluxos enquanto forem validados.

### Multi-clínica e isolamento

Modelo inicial recomendado: banco PostgreSQL compartilhado com `clinic_id` obrigatório nas tabelas pertencentes a uma clínica. O backend obtém a clínica ativa a partir da sessão/token autenticado e valida o vínculo do usuário em toda operação. O cliente não escolhe livremente o `clinic_id` para acessar dados.

Regras obrigatórias:
1. Toda consulta e alteração de dados clínicos deve ser limitada à clínica autenticada.
2. Chaves estrangeiras e restrições devem impedir associações entre registros de clínicas diferentes.
3. Usuários e perfis são vinculados a uma ou mais clínicas conforme a regra de negócio definida.
4. Testes automatizados devem tentar acessar registros de outra clínica e comprovar resposta negada/sem dados.
5. PostgreSQL Row-Level Security pode ser adotado como defesa adicional, nunca como substituto da autorização no backend.
6. Ações sensíveis devem gerar trilha de auditoria sem registrar conteúdo clínico desnecessário em logs.

### Domínios iniciais do banco
- `clinics`: clínicas, status, dados institucionais e configurações.
- `users`: identidade, credenciais e status.
- `clinic_memberships`: vínculo usuário-clínica, perfil e permissões.
- `professionals`: dados profissionais vinculados à clínica.
- `patients`: cadastro de pacientes por clínica.
- `appointments` / `queue_entries`: fluxo de chegada, fila, chamadas e status.
- `service_types` / `rooms`: serviços e salas/consultórios.
- `triage_records`: sinais vitais e autoria/data de registro.
- `encounters`: atendimento clínico.
- `clinical_notes`: anamnese, exame, avaliação e conduta, com controle de autoria.
- `attachments`: metadados de documentos e mídias; arquivos fora do banco em armazenamento privado.
- `audit_events`: trilha de auditoria.

O desenho definitivo será refinado após mapear todos os campos e regras dos formulários atuais. Não será feita migração automática de dados de teste ou JSON sem inventário, cópia de segurança e validação de integridade.

## 4. Plano de execução — sete etapas

1. **Auditoria aprofundada:** inventariar todas as telas, campos, rotas, estados, arquivos, regras e dependências; registrar o comportamento atual como referência.
2. **Diagnóstico e especificação:** classificar lacunas, definir papéis, permissões, modelo multi-clínica, política de dados e critérios de aceite.
3. **Fundação Java sem quebrar a interface:** criar projeto Spring Boot, configuração, API versionada, PostgreSQL, Flyway, health checks e ambiente Docker.
4. **Segurança e multi-tenancy:** login, gestão de sessão/token, perfis, vínculo com clínica, autorização em todas as rotas, auditoria e testes negativos de isolamento.
5. **Migração incremental dos módulos:** profissionais e configurações; pacientes; fila e chamadas; triagem; prontuário/atendimento; anexos e mídia. A cada módulo, comparar comportamento e dados com a versão de referência.
6. **Testes e preparação de produção:** testes unitários, integração, API, concorrência, autorização, isolamento entre clínicas, backup/restauração, desempenho e revisão de segurança.
7. **VPS e homologação externa:** provisionar Linux, Docker, PostgreSQL, domínio/HTTPS, firewall, segredos, backups, monitoramento e rotina de atualização/rollback; validar acessos de clínicas distintas.

## 5. Critérios mínimos de aceite

- Fluxos existentes permanecem disponíveis e equivalentes durante a migração.
- Duas clínicas de teste não conseguem visualizar, alterar, pesquisar ou inferir dados uma da outra.
- Usuários só executam ações autorizadas para seu perfil e clínica.
- Operações críticas são transacionais e resistentes a atualizações concorrentes.
- Prontuários e anexos têm controle de acesso, trilha de auditoria e política de retenção/backup.
- Backups são criptografados e uma restauração real é testada antes de produção.
- HTTPS, gestão segura de segredos, logs sem dados sensíveis e monitoramento estão ativos.
- Testes automatizados passam no CI antes de cada implantação.

## 6. LGPD e operação clínica

Dados de saúde são dados pessoais sensíveis. Antes de produção, a clínica responsável deverá definir controlador/operadores, bases legais, perfis de acesso, retenção, atendimento aos direitos dos titulares, resposta a incidentes e contratos com fornecedores. A implementação técnica reduz riscos, mas não substitui avaliação jurídica, governança clínica e validação operacional.

## 7. Limites desta auditoria

Esta primeira entrega foi baseada na leitura do README, páginas HTML principais, script PowerShell e arquivos de inicialização encontrados na raiz. Ainda falta inventariar integralmente todos os scripts inline, recursos auxiliares, regras de negócio de cada tela, arquivos de dados de exemplo, configuração de CI e histórico de alterações. Por isso, este documento é a linha de base técnica, não uma declaração de que a auditoria funcional e de segurança está concluída.

**Regra de preservação:** não alterar diretamente a `main`; toda evolução ocorre nesta branch de migração, com commits pequenos e verificáveis. Nenhuma funcionalidade existente deve ser removida sem equivalência implementada e testada.
