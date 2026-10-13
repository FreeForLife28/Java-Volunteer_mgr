# VolunteerHub

Volunteer management platform with three roles (Admin, Organization, Volunteer).

- Frontend: plain HTML, CSS and JavaScript (`src/main/webapp`)
- Backend: Java servlets (Servlet 4.0) packaged as a WAR, plain JDBC
- Database: MySQL 8

## What you need installed

- JDK 11 or newer
- MySQL 8 (installed locally; `jm migrate <database_name>` can start its Windows service)

## Setup

1. Open **Command Prompt** in the project folder and run `jm init`. The tracked root
   `jm.cmd` means no separate `jm` installation or PATH change is needed. `jm init`
   checks for Maven and Tomcat 9; if either is missing, it downloads the Apache binary
   archive into the ignored `.jm/tools` folder and verifies its SHA-512 checksum. It
   creates `pom.xml` from the tracked template if it is missing, then resolves Maven
   dependencies. A JDK 11+ is required to compile the launcher.
2. Configure the local database with `jm migrate volunteer_db` (replace the name if
   needed). It checks for a local MySQL server, starts a stopped Windows service when
   available, creates the database, applies `database/schema.sql`, creates or updates
   the `volunteer_app` account, and saves connection settings to the ignored
   `src/main/resources/db.properties`. Enter the MySQL administrator password when
   prompted. Set `JM_DB_ADMIN_USER` if the admin account is not `root`. If `DB_URL` is
   set, its database name must match the command argument. Existing app credentials
   are reused. If both `DB_USER` and `DB_PASSWORD` are set, those credentials are used;
   otherwise a password is generated only if `db.properties` does not already contain
   one.
3. Run `jm run volunteer.war` from the project folder. It builds the WAR with Maven,
   copies it to Tomcat 9, starts Tomcat, waits for the application to be ready, and
   opens it in your default browser. The terminal prints the detected HTTP port and URL.

`jm migrate <database_name>` is the database setup command. `jm run <name.war>` builds,
deploys, and starts the app. The optional `tools/jm/install-jm.ps1` is only needed if
you want to call `jm` from outside the project folder.

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
