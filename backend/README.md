# MARKYS Gestão Clínica — Backend

Backend Java 21 / Spring Boot 3.5 com PostgreSQL e Flyway. Esta base é paralela à aplicação HTML/PowerShell existente; não substitui nem remove os arquivos legados.

## Requisitos
- JDK 21
- Maven 3.9+
- Docker com Docker Compose

## Banco local
Na raiz do repositório:

```bash
docker compose -f docker-compose.dev.yml up -d postgres
```

## Executar a API
Na pasta `backend/`:

```bash
mvn spring-boot:run
```

Configuração por variáveis:
- `DATABASE_URL` (padrão local: `jdbc:postgresql://localhost:5432/markys`)
- `DATABASE_USERNAME` (padrão: `markys`)
- `DATABASE_PASSWORD` (padrão de desenvolvimento apenas: `markys_dev_only`)
- `PORT` (padrão: `8080`)

Health check: `GET /api/v1/health`.

## Segurança e estado
Esta entrega é apenas a fundação técnica. O endpoint de saúde não retorna dados clínicos. Não há ainda login, cadastro de pacientes ou endpoints de prontuário. Não exponha a API à internet nem use dados reais nesta fase. Antes de ativar qualquer dado clínico serão implementados autenticação, autorização por clínica, contexto de tenant validado no servidor, RLS/controles equivalentes, auditoria e testes de isolamento.

Nunca use as credenciais padrão em produção. Segredos de produção devem ser fornecidos por ambiente/gerenciador de segredos.
