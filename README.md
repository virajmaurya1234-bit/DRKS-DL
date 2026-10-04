# DRKS - Télécharger (Java backend)

Upload any photo, video or file. It is stored on the server and anyone can download it. No account needed.

The backend is one Java file (`src/Server.java`). It uses only the Java that is already installed, so there is nothing to download or build.

## Run it

Requires Java 17 or newer (JDK, not only JRE). Check with `java -version`.

    java src/Server.java

or double-click / run `run.bat` (Windows) or `./run.sh` (Mac, Linux).

Open http://localhost:3000 for the landing page and http://localhost:3000/files.html to upload and download.

## Run with Docker

    docker build -t drks-telecharger .
    docker run -p 3000:3000 -v drks-data:/data drks-telecharger

## What it does

- Upload any file type, many at once, with progress, speed, time left and retry
- Files stream straight to disk, so a huge video never fills memory
- Photos and videos preview in the browser, and videos can seek
- Every file has a download button and a copyable link, plus search by name and a download count
- All other file types are served as downloads only, so an uploaded web page can never run in the browser
- The file type is read from the file's first bytes, not its name
- Whoever uploaded a file can delete it from the same browser. Nobody else can
- Limits: 20 files per request, 60 uploads per 10 minutes per IP, a max file size, and an optional total cap

## Where files live

`DATA_DIR/files/` holds the files and `DATA_DIR/files.tsv` is the list of file names, sizes and download counts. Back up the whole `DATA_DIR` folder.

## Settings

Set as environment variables (see `.env.example`): PORT, DATA_DIR, PUBLIC_MAX_FILE_SIZE, PUBLIC_MAX_TOTAL, TRUST_PROXY.

## Not included in the Java version yet

User accounts, albums, share links, admin panel, ZIP download, S3/R2 storage. The earlier Node.js version had accounts and albums. For millions of files, move the file list into a database such as MongoDB or PostgreSQL.
