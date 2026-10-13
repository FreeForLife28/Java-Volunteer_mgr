# VolunteerHub: simple setup guide

Follow these steps to download, set up, and run VolunteerHub on your computer. Run the commands from a terminal opened in the project folder unless a step says otherwise.

## 1. Download the project

Install GitHub Desktop if you do not already have it. In GitHub Desktop, choose **File > Clone repository**, open the **URL** tab, and enter:

`https://github.com/FreeForLife28/Java-Volunteer_mgr.git`

Choose where to save the project and click **Clone**. Then choose **Repository > Open in Terminal** (or **Open in Command Prompt** on Windows). This opens a terminal in the right folder.

If you already know how to use a terminal, you can clone it with:

```text
git clone https://github.com/FreeForLife28/Java-Volunteer_mgr.git
cd Java-Volunteer_mgr
```

## 2. Install Java and MySQL

Before continuing, install:

- **JDK 11 or newer**. Install a JDK, not only Java. The JDK includes the `javac` compiler needed by the installer.
- **MySQL 8**, including its command-line client. During installation, remember the MySQL administrator password you choose.

Keep MySQL on this computer and use its default port, `3306`, unless you already know you changed it. Maven and Tomcat do not need to be installed by you; `jm init` downloads them when needed.

## 3. Install the `jm` command

On Windows, open the project folder in File Explorer and double-click:

`tools/installer/install.cmd`

On macOS or Linux, open a terminal in the project folder and run:

```sh
sh tools/installer/install.sh
```

This installs `jm` for your computer user. It does not install Java for you.

## 4. Open a new terminal and check the installation

Close the terminal and open a new one in the project folder. Type:

```text
jm -v
```

You should see:

```text
Hello From Java manager
```

If the command is not found, make sure you opened a new terminal after installing `jm`. If the installer reported that Java tools were missing, install the JDK first and run the installer again.

## 5. Prepare the project

In the project folder, run:

```text
jm init
```

The first run may take a while because it downloads Maven and Tomcat and prepares the application. Leave the terminal open until it finishes.

## 6. Create and set up the database

Run:

```text
jm migrate volunteer_db
```

When asked, type the MySQL administrator password you created when installing MySQL. The password does not appear while you type; that is normal. This command creates the `volunteer_db` database, adds the app's tables, and saves the app's local connection settings.

## 7. Start the app

Run:

```text
jm run volunteer.war
```

Wait while the app builds and starts. `jm` will open the app in your browser. If it does not, copy the **Application URL** printed in the terminal and paste it into your browser. It will look like `http://localhost:8080/volunteer/`; the port may be different on your computer.

Keep the terminal open while using the app. On first sign-in, use the starter account shown in `README.md`, then change its password.

## 8. Optional: uninstall `jm`

This removes the user-level `jm` command. It does not delete the project folder or its files.

- Windows: double-click `tools/installer/uninstall.cmd`.
- macOS or Linux, from the project folder:

  ```sh
  sh tools/installer/uninstall.sh
  ```

## 9. Optional: put your own copy on GitHub

You do not need to do this to run the app. The project you cloned is already a Git repository, so you do **not** need to run `git init`.

To publish your own copy, first create a new, empty repository under your GitHub account. Then, in the project terminal, replace `YOUR-USERNAME` and `YOUR-REPOSITORY` below with your GitHub username and the new repository name, and run:

```text
git remote set-url origin https://github.com/YOUR-USERNAME/YOUR-REPOSITORY.git
git add .
git commit -m "Set up VolunteerHub"
git push -u origin main
```

If Git says there is nothing to commit, there are no new changes to publish yet. Do not add passwords or other private information to GitHub. The local database settings and downloaded tools are excluded from the project upload automatically.
