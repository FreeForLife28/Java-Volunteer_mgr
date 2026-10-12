# VolunteerHub

Volunteer management platform with three roles (Admin, Organization, Volunteer).

- Frontend: plain HTML, CSS and JavaScript (`src/main/webapp`)
- Backend: Java servlets (Servlet 4.0) packaged as a WAR, plain JDBC
- Database: MySQL 8

## What you need installed

- JDK 11 or newer
- Maven 3.6+
- MySQL 8 (installed locally; `jm migrate <database_name>` can start its Windows service)
- Apache Tomcat 9.x (not 10+, which uses the `jakarta.*` namespace)

## Setup

1. Install the `jm` command once, from a VS Code terminal:
   `powershell -NoProfile -ExecutionPolicy Bypass -File tools\jm\install-jm.ps1`
   This requires JDK 11+ and adds the command to your Windows user PATH.
2. Run `jm migrate volunteer_db` from the project folder (replace `volunteer_db` with
   the database name you want). It checks for a local MySQL server, starts a stopped
   Windows service when available, creates the database, applies
   `database/schema.sql`, creates or updates the `volunteer_app` account, and saves
   connection settings to the ignored `src/main/resources/db.properties`. Enter the
   MySQL administrator password when prompted. Set `JM_DB_ADMIN_USER` if the admin
   account is not `root`. If `DB_URL` is set, its database name must match the command
   argument. Existing app credentials are reused. If both `DB_USER` and
   `DB_PASSWORD` are set, those credentials are used; otherwise a password is
   generated only if `db.properties` does not already contain one.
3. Run `jm run volunteer.war` from the project folder. It starts a stopped local MySQL
   Windows service when available, builds the WAR with Maven, copies it to Tomcat 9,
   starts Tomcat, waits for the application to be ready, and opens it in your default
   browser. Set `CATALINA_HOME` or `TOMCAT_HOME`, or enter the Tomcat folder when prompted.
   The terminal prints the detected HTTP port and application URL.

The VS Code task **Install jm command** performs the one-time install; the task
**Run VolunteerHub with jm** runs the app. Run `jm migrate <database_name>` in a VS
Code terminal for database setup. If the command is not recognized in an existing
terminal, open a new VS Code terminal.

### Database credential safety

Keep MySQL passwords local. Do not add real credentials to source control, commit them
in a `.properties` file, or share them in chat. `db.properties` is included in the
built WAR, so use environment variables for deployments and treat locally built WAR
files as confidential when they contain local credentials. If a password is exposed,
rotate it in MySQL and update the app configuration.

## First sign-in

On first start the app creates an administrator:

- Email: `admin@volunteerhub.local`
- Password: `Admin@123`

Change that password from Admin > User management > Edit.
Organizations and volunteers can register themselves on the sign-in page.

## How it fits the brief

| Role         | Feature                                               | Where                                |
| ------------ | ----------------------------------------------------- | ------------------------------------ |
| Admin        | User management (create/update/delete, roles, status) | Admin > User management              |
| Admin        | Approve/reject listings                               | Admin > Volunteer listings, Overview |
| Admin        | System settings                                       | Admin > System settings              |
| Admin        | Participation statistics, pending approvals           | Admin > Participation overview       |
| Organization | Post/edit/delete opportunities                        | Post opportunities                   |
| Organization | Messages and feedback to volunteers                   | Volunteer interaction                |
| Organization | Participation reports, mark attended/no-show          | Participation tracking               |
| Volunteer    | Browse and sign up                                    | Available opportunities              |
| Volunteer    | Sign-up status, cancel                                | My sign-ups                          |
| Volunteer    | Log and view hours                                    | Hours tracking                       |
| Volunteer    | Messages and feedback to organizations                | Organizations                        |

## REST API (all JSON)

- `POST /api/auth/login | register | logout`, `GET /api/auth/me | settings`
- Admin: `GET|POST /api/admin/users`, `PUT|DELETE /api/admin/users/{id}`, `GET /api/admin/listings`,
  `POST /api/admin/listings/{id}/status`, `GET|PUT /api/admin/settings`, `GET /api/admin/stats`
- Organization: `GET|POST /api/org/listings`, `PUT|DELETE /api/org/listings/{id}`,
  `GET /api/org/participation`, `POST /api/org/participation/{signupId}`
- Volunteer: `GET /api/volunteer/opportunities`, `GET|POST /api/volunteer/signups`,
  `DELETE /api/volunteer/signups/{id}`, `GET|POST /api/volunteer/hours`, `DELETE /api/volunteer/hours/{id}`
- Messages: `GET|POST /api/messages`, `GET /api/messages/contacts`

## Notes

- Passwords are hashed with BCrypt. Sessions are server-side (`HttpSession`). Every API call re-checks that the
  account is still active, and each area (`/api/admin`, `/api/org`, `/api/volunteer`) is role-protected in `AuthFilter`.
- Confirming attendance (Participation tracking) credits the listing's hours to the volunteer automatically.
- Each query opens its own JDBC connection, which is fine locally. For heavier use, swap `Db.conn()` for a connection pool.
