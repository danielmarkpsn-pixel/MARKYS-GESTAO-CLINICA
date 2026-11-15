# MARKYS Gestão Clínica — Cloudflare

Esta pasta contém o Worker de entrada e a configuração do Cloudflare Containers. O serviço Java continua em `../backend`; o Docker build context aponta para essa pasta para que o Dockerfile atual mantenha os caminhos `pom.xml` e `src`.

## Arquitetura

- Cloudflare Worker: recebe HTTPS e encaminha as requisições para o backend.
- Cloudflare Container: executa o backend Spring Boot / Java 21 na porta 8080.
- Durable Object: mantém o identificador singleton do backend e controla o ciclo de vida do container.
- Neon PostgreSQL: banco relacional externo. Use a connection string JDBC com SSL e endpoint direto (Direct) durante a primeira implantação e execução do Flyway.
- Cloudflare R2: bucket privado para PDFs de pacientes, acessado pelo SDK S3 do Java.

O container é configurado com instância `standard-1` e janela de inatividade de dois minutos. O perfil `lite` é o menor disponível e não foi escolhido para esta aplicação Spring Boot. O tamanho `standard-1` deve ser validado em teste e acompanhado no painel de custos antes de uso contínuo. A instância pode ser encerrada após inatividade; a primeira requisição seguinte pode sofrer cold start.

## Pré-requisitos

1. Conta Cloudflare com Workers Paid e Containers habilitados.
2. Projeto Neon PostgreSQL criado e migrations do backend revisadas.
3. Bucket R2 criado como privado, sem domínio público.
4. Docker em execução para o primeiro deploy local com Wrangler.
5. Node.js LTS e npm.

## Configurar o Neon

No Neon, crie um banco/projeto exclusivo para MARKYS. Na tela Connect do projeto, selecione a branch e o banco que você criou e escolha **Direct connection** (não Pooled) nesta primeira implantação. O backend executa Flyway na inicialização; o endpoint direto evita introduzir o pooler durante a criação/validação do esquema. Converta os dados para o formato JDBC abaixo, substituindo host e nome do banco pelos valores exibidos no Neon:

```
jdbc:postgresql://HOST.REGION.aws.neon.tech/NOME_DO_BANCO?sslmode=require
```

Cadastre o host e o nome do banco na variável `DATABASE_URL`; usuário e senha ficam nas variáveis separadas `DATABASE_USERNAME` e `DATABASE_PASSWORD`. Não inclua `postgresql://` no lugar de `jdbc:postgresql://`.

Use o usuário e senha fornecidos pelo Neon nos secrets separados abaixo. Não coloque credenciais em arquivos versionados. Antes de usar dados reais, confirme que as migrations executam e que o RLS está ativo em todas as tabelas clínicas.

## Configurar o R2

1. Crie um bucket privado, por exemplo `markys-private-documents`.
2. Gere um token R2 com Object Read & Write limitado somente a esse bucket.
3. Anote o endpoint S3 da conta: `https://<ACCOUNT_ID>.r2.cloudflarestorage.com`.
4. Não habilite acesso público ao bucket.

## Configurar os secrets do Worker

No diretório `cloudflare`, execute `npm install` e cadastre os valores pelo Wrangler. Os comandos solicitam cada segredo sem gravá-lo no repositório:

```bash
npx wrangler secret put DATABASE_URL
npx wrangler secret put DATABASE_USERNAME
npx wrangler secret put DATABASE_PASSWORD
npx wrangler secret put MARKYS_PII_ENCRYPTION_KEY
npx wrangler secret put MARKYS_PII_FINGERPRINT_KEY
npx wrangler secret put MARKYS_MASTER_BOOTSTRAP_TOKEN
npx wrangler secret put R2_ENDPOINT
npx wrangler secret put R2_BUCKET
npx wrangler secret put R2_ACCESS_KEY_ID
npx wrangler secret put R2_SECRET_ACCESS_KEY
```

Gere duas chaves Base64 independentes de 32 bytes para CPF, fora do repositório:

```bash
openssl rand -base64 32
openssl rand -base64 32
```

O token MASTER também deve ser aleatório, forte e mantido apenas durante o bootstrap inicial. Depois de criar a conta MASTER, remova-o com `npx wrangler secret delete MARKYS_MASTER_BOOTSTRAP_TOKEN`.

## Deploy

A partir desta pasta:

```bash
npm install
npx wrangler deploy
```

O Wrangler constrói a imagem usando `../backend/Dockerfile` e o contexto `../backend`. O Docker precisa estar disponível no ambiente que executa o deploy. Após o primeiro deploy, aguarde o provisionamento do Container.

## Verificação

```bash
npx wrangler containers list
```

Teste:

- `GET /api/v1/health` deve responder com `status: ok`.
- `GET /api/v1/auth/csrf` deve iniciar o fluxo de autenticação e emitir o cookie CSRF.
- Login, seleção de clínica e logout devem funcionar com cookies HTTPS.
- Upload e download de PDF devem continuar autenticados e limitados à clínica da sessão.
- Confirme no R2 que o objeto é criado e removido corretamente; o bucket não deve ser público.

## Limites e custos

A instância basic usa 1 GiB de memória. O plano Workers Paid tem uma franquia mensal limitada de Container-hours; um backend continuamente ativo pode gerar cobrança adicional. A janela de inatividade reduz tempo ocioso faturável, mas implica cold starts. Configure um limite de gastos no painel Cloudflare antes de expor o serviço e monitore consumo, logs e erros.

## Segurança / homologação

- Não use dados reais de pacientes durante o primeiro teste.
- Não compartilhe nem versione secrets, chaves PII, credenciais Neon ou tokens R2.
- O bucket R2 permanece privado; os PDFs são servidos somente pelas rotas autenticadas do backend.
- A camada Worker não substitui a autorização por clínica do Spring Security nem o RLS do PostgreSQL.
- Valide backup e restauração do Neon e a política de retenção/backup dos objetos R2 antes de produção.
