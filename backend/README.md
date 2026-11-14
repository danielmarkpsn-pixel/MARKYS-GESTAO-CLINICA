# MARKYS Gestão Clínica — Backend

Backend multi-clínica em Java 21 / Spring Boot 3.5 / PostgreSQL.

## Desenvolvimento

1. Inicie o PostgreSQL: `docker compose -f ../docker-compose.dev.yml up -d`.
2. Configure `DATABASE_URL`, `DATABASE_USERNAME` e `DATABASE_PASSWORD` se não usar os padrões locais.
3. Execute `mvn spring-boot:run`.

## Primeiro acesso

Após aplicar as migrações Flyway, envie para a API:

`POST /api/v1/auth/setup`

Campos: `clinicName`, `fullName`, `email` e `password`. A senha deve ter ao menos 12 caracteres. A configuração só é aceita enquanto não existir nenhuma clínica ou usuário; ela cria a primeira clínica e seu administrador e inicia a sessão.

## Autenticação

- `GET /api/v1/auth/csrf`: inicializa e retorna o token CSRF.
- `POST /api/v1/auth/login`: autentica e cria sessão HTTP.
- `GET /api/v1/auth/me`: retorna usuário e vínculos ativos com clínicas.
- `POST /api/v1/auth/logout`: encerra a sessão.

A sessão usa cookie HttpOnly, SameSite=Lax e proteção CSRF por cookie/header. Em produção, habilite HTTPS e defina `SESSION_COOKIE_SECURE=true`. O frontend deve enviar cookies nas chamadas same-origin e enviar o valor de `XSRF-TOKEN` no header `X-XSRF-TOKEN` em operações POST/PUT/PATCH/DELETE.

Após cinco falhas consecutivas, a conta é bloqueada por 15 minutos. Respostas de login inválido não revelam se o e-mail existe.

## Segurança e estágio

As rotas clínicas permanecem protegidas por autenticação. O contexto de clínica e a autorização por perfil precisam ser aplicados a cada API clínica antes de habilitar dados reais. O login inicial não representa, isoladamente, autorização multi-clínica completa.

Não exponha a API sem HTTPS, segredos de produção e isolamento de tenant validado. Não use dados reais de pacientes até a conclusão da homologação de segurança.
