-- VolunteerHub schema (MySQL 8+)
CREATE DATABASE IF NOT EXISTS volunteer_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE volunteer_db;

CREATE TABLE IF NOT EXISTS users (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  name          VARCHAR(100) NOT NULL,
  email         VARCHAR(150) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,
  role          ENUM('ADMIN','ORGANIZATION','VOLUNTEER') NOT NULL,
  status        ENUM('ACTIVE','SUSPENDED') NOT NULL DEFAULT 'ACTIVE',
  created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS listings (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  org_id      BIGINT NOT NULL,
  title       VARCHAR(150) NOT NULL,
  description TEXT NOT NULL,
  event_date  DATE NOT NULL,
  location    VARCHAR(200) NOT NULL,
  hours       DECIMAL(5,2) NOT NULL DEFAULT 2,
  capacity    INT NOT NULL DEFAULT 10,
  status      ENUM('PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'PENDING',
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_listing_org FOREIGN KEY (org_id) REFERENCES users(id) ON DELETE CASCADE,
  INDEX idx_listing_status_date (status, event_date)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS signups (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  listing_id   BIGINT NOT NULL,
  volunteer_id BIGINT NOT NULL,
  status       ENUM('SIGNED_UP','ATTENDED','NO_SHOW','CANCELLED') NOT NULL DEFAULT 'SIGNED_UP',
  created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_signup (listing_id, volunteer_id),
  CONSTRAINT fk_signup_listing   FOREIGN KEY (listing_id)   REFERENCES listings(id) ON DELETE CASCADE,
  CONSTRAINT fk_signup_volunteer FOREIGN KEY (volunteer_id) REFERENCES users(id)    ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS volunteer_hours (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  volunteer_id BIGINT NOT NULL,
  listing_id   BIGINT NULL,
  signup_id    BIGINT NULL UNIQUE,
  hours        DECIMAL(5,2) NOT NULL,
  log_date     DATE NOT NULL,
  notes        VARCHAR(255) NULL,
  source       ENUM('SELF','VERIFIED') NOT NULL DEFAULT 'SELF',
  created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_hours_volunteer FOREIGN KEY (volunteer_id) REFERENCES users(id)    ON DELETE CASCADE,
  CONSTRAINT fk_hours_listing   FOREIGN KEY (listing_id)   REFERENCES listings(id) ON DELETE SET NULL,
  CONSTRAINT fk_hours_signup    FOREIGN KEY (signup_id)    REFERENCES signups(id)  ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS messages (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  sender_id   BIGINT NOT NULL,
  receiver_id BIGINT NOT NULL,
  kind        ENUM('MESSAGE','FEEDBACK') NOT NULL DEFAULT 'MESSAGE',
  body        TEXT NOT NULL,
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_msg_sender   FOREIGN KEY (sender_id)   REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_msg_receiver FOREIGN KEY (receiver_id) REFERENCES users(id) ON DELETE CASCADE,
  INDEX idx_msg_receiver (receiver_id), INDEX idx_msg_sender (sender_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS settings (
  setting_key   VARCHAR(50) PRIMARY KEY,
  setting_value VARCHAR(255) NOT NULL
) ENGINE=InnoDB;

INSERT IGNORE INTO settings (setting_key, setting_value) VALUES
  ('site_name', 'VolunteerHub'),
  ('contact_email', 'support@volunteerhub.local'),
  ('allow_registration', 'true'),
  ('require_listing_approval', 'true'),
  ('max_active_signups', '0');

-- The default admin account (admin@volunteerhub.local / Admin@123) is created
-- automatically by the app on first start (see AppListener.java).
