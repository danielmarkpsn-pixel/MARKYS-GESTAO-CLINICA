# MARKYS Gestão Clínica — Backend

Backend multi-clínica em Java 21 / Spring Boot 3.5 / PostgreSQL.

## Desenvolvimento

1. Inicie o PostgreSQL: `docker compose -f ../docker-compose.dev.yml up -d`.
2. Configure `DATABASE_URL`, `DATABASE_USERNAME` e `DATABASE_PASSWORD` se não usar os padrões locais.
3. Execute `mvn spring-boot:run`.

## Primeiro acesso — criação do MASTER

O cadastro público de clínicas está desativado. A primeira conta global é criada uma única vez por um procedimento protegido por segredo de inicialização.

1. Gere um segredo aleatório forte (pelo menos 32 bytes) fora do repositório.
2. Configure no ambiente do servidor `MARKYS_MASTER_BOOTSTRAP_TOKEN`.
3. Inicialize o backend e obtenha o token CSRF em `GET /api/v1/auth/csrf`.
4. Envie `POST /api/v1/auth/master-bootstrap` com o header `X-MARKYS-BOOTSTRAP-TOKEN` e o token CSRF no header `X-XSRF-TOKEN`. Corpo JSON:

```json
{
  "fullName": "Administrador da Plataforma",
  "email": "master@exemplo.com",
  "password": "UmaSenhaLongaComMaisDe12Caracteres"
}
```

O segredo não deve ser colocado no código, no frontend, em arquivos versionados ou em logs. Depois da criação, o endpoint não permite outra conta MASTER, mesmo que a variável continue configurada. Remova `MARKYS_MASTER_BOOTSTRAP_TOKEN` do ambiente após a inicialização.

## Administração MASTER

Após entrar com a conta MASTER:

- `GET /api/v1/master/clinics`: lista clínicas e indicadores básicos de usuários/administradores.
- `POST /api/v1/master/clinics`: cria uma clínica e seu primeiro administrador local na mesma transação. Campos: `clinicName`, `adminName`, `adminEmail`, `adminPassword`.
- `PATCH /api/v1/master/clinics/{clinicId}/status`: altera o status para `ACTIVE`, `SUSPENDED` ou `INACTIVE`.

A senha inicial do administrador local é definida pelo MASTER no cadastro e deve ser compartilhada por canal seguro. O administrador local recebe somente o vínculo `CLINIC_ADMIN` daquela clínica. O MASTER é uma identidade global separada, sem vínculo artificial a uma clínica. O acesso a `/api/v1/master/**` exige a autoridade `ROLE_MASTER`.

## Autenticação

- `GET /api/v1/auth/csrf`: inicializa e retorna o token CSRF.
- `POST /api/v1/auth/login`: autentica e cria sessão HTTP.
- `GET /api/v1/auth/me`: retorna usuário, `globalRole`, vínculos e clínica ativa.
- `POST /api/v1/auth/select-clinic`: seleciona uma clínica à qual o usuário possui vínculo ativo.
- `POST /api/v1/auth/logout`: encerra a sessão.

A rota legada `POST /api/v1/auth/setup` permanece bloqueada e responde HTTP 403. A criação de clínicas é exclusiva do MASTER.

A sessão usa cookie HttpOnly, SameSite=Lax e proteção CSRF por cookie/header. Em produção, habilite HTTPS e defina `SESSION_COOKIE_SECURE=true`. O frontend deve enviar cookies nas chamadas same-origin e enviar o valor de `XSRF-TOKEN` no header `X-XSRF-TOKEN` em operações POST/PUT/PATCH/DELETE.

Após cinco falhas consecutivas, a conta é bloqueada por 15 minutos. Respostas de login inválido não revelam se o e-mail existe.

## Segurança e estágio

As rotas clínicas permanecem protegidas por autenticação. O contexto de clínica e a autorização por perfil precisam ser aplicados a cada API clínica antes de habilitar dados reais. A seleção de clínica fica guardada na sessão e é validada contra o vínculo ativo do usuário. O filtro de segurança valida esse vínculo em cada rota protegida de API, abre uma transação para a requisição e define `app.current_clinic_id` de forma transacional. A migration V5 ativa e força Row-Level Security (RLS) no PostgreSQL para pacientes, profissionais, salas, fila, triagem, atendimentos e notas clínicas. Mesmo que uma consulta esqueça o filtro `clinic_id`, o banco restringe as linhas ao tenant da transação. `TenantContext.requireClinicId()` também disponibiliza o identificador para a camada de serviço. Cada novo repositório/serviço clínico ainda deve filtrar explicitamente pelo tenant e aplicar as permissões do perfil.

Não exponha a API sem HTTPS, segredos de produção e isolamento de tenant validado. Não use dados reais de pacientes até a conclusão da homologação de segurança.

## Cadastro de pacientes e proteção de dados

O cadastro de pacientes usa a API tenant-scoped `/api/v1/clinic/patients`. CPF nunca é persistido em texto aberto: o servidor exige duas chaves independentes, uma para AES-256-GCM e outra para HMAC-SHA-256 (deduplicação). Gere-as fora do repositório:

```bash
openssl rand -base64 32
openssl rand -base64 32
```

Configure os resultados como variáveis secretas do ambiente do backend:

- `MARKYS_PII_ENCRYPTION_KEY`: chave Base64 de 32 bytes para criptografia autenticada.
- `MARKYS_PII_FINGERPRINT_KEY`: chave Base64 independente de 32 bytes para o fingerprint do CPF.
- `MARKYS_PRIVATE_STORAGE_PATH`: diretório persistente e privado para PDFs; padrão `/var/lib/markys/private`.

As chaves devem ser diferentes, estáveis entre reinicializações e armazenadas em secret manager. Não as gere a cada inicialização, não as versione e não as coloque no frontend. Perder/rotacionar essas chaves exige um plano formal de recriptografia dos dados existentes.

O upload aceita somente PDF com assinatura de arquivo validada, até 10 MB. Os arquivos são gravados fora do diretório estático e só podem ser baixados por endpoint autenticado e autorizado da mesma clínica. Em produção, monte `MARKYS_PRIVATE_STORAGE_PATH` em volume persistente, criptografado e incluído no plano de backup/restauração. Não habilite dados reais até validar permissões do volume, backup, recuperação e configuração das chaves.

O formulário calcula a idade no navegador a partir da data de nascimento; a idade não é armazenada. O cadastro cria o paciente e sua entrada na fila em uma transação, registrando snapshots de nomes de exames e salas para preservar o histórico. Na criação de cada clínica, o MASTER provisiona automaticamente as dez salas/consultórios, nove tipos de atendimento e o catálogo inicial de exames especificado em `docs/ESPECIFICACAO-RECEPCAO.md`. O formulário permite editar os dados cadastrais e, quando há entrada ativa na fila, atualizar o tipo de atendimento e os exames selecionados.
