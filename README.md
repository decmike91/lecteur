# Lecteur

Application Android simple pour **lire** vos documents — sans publicité, sans connexion Internet, sans modification des fichiers.

## Télécharger

👉 **[Dernière version (APK)](https://github.com/decmike91/lecteur/releases/latest)** — téléchargez le fichier `Lecteur-x.y.z.apk` depuis votre téléphone puis ouvrez-le pour l'installer (autorisez l'installation depuis votre navigateur si Android le demande).

Chaque modification poussée sur `main` est compilée automatiquement par GitHub Actions et publiée dans *Releases*.

## Formats pris en charge

| Type | Formats |
|---|---|
| PDF | `.pdf` (rendu natif Android, zoom) |
| Word | `.docx` (texte, titres, listes, tableaux, images), `.doc` (texte) |
| Excel | `.xlsx` (toutes les feuilles, cellules fusionnées, dates), `.xls`, `.csv` |
| PowerPoint | `.pptx` (diapositives avec mise en page, texte, images, tableaux), `.ppt` (texte et images) |
| OpenDocument | `.odt`, `.ods`, `.odp` |
| Texte | `.txt`, `.rtf`, `.md`, `.json`, `.xml`, `.log`, code source… |
| Images | `.jpg`, `.png`, `.gif`, `.webp`, `.bmp`, `.svg`… |
| Archives | `.zip`, `.7z`, `.rar` (RAR 4), `.tar`, `.tar.gz`/`.tgz`, `.gz`, `.bz2`, `.xz` — navigation dans les dossiers et ouverture directe des fichiers contenus |

## Fonctionnalités

- Ouverture via le bouton **Ouvrir un document**, depuis un gestionnaire de fichiers (« Ouvrir avec… ») ou depuis le partage d'une autre appli.
- Liste des **fichiers récents**.
- Bouton **Partager** : **e-mail**, **SMS/MMS**, **WhatsApp** ou toute autre application.
- Zoom par pincement sur tous les documents.
- **Lecture seule** : l'application travaille sur une copie temporaire, le fichier d'origine n'est jamais modifié.
- **Aucune permission Internet** : pas de publicité, pas de pistage, rien n'est envoyé.

## Compiler soi-même

Ouvrir le dossier dans Android Studio, ou :

```bash
./gradlew assembleRelease
```

Par défaut, l'APK est signé avec la clé `app/signing/lecteur.jks` incluse dans le dépôt : la signature reste identique d'une version à l'autre, donc les mises à jour s'installent par-dessus l'ancienne version. Cette clé étant publique, vous pouvez la remplacer par une clé privée en ajoutant dans *Settings → Secrets and variables → Actions* les secrets `KEYSTORE_BASE64` (fichier .jks encodé en base64), `KEYSTORE_PASSWORD`, `KEY_ALIAS` et `KEY_PASSWORD` (il faudra alors désinstaller une fois l'ancienne version).

## Bibliothèques

- [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) + XZ for Java — archives
- [junrar](https://github.com/junrar/junrar) — archives RAR
- [Apache POI](https://poi.apache.org/) — anciens formats `.doc`, `.xls`, `.ppt`
