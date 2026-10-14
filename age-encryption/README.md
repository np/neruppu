# neruppu-age — chiffrement des captures à la source

Prototype : chiffrer photos et clips audio **au moment de la capture**, vers une
clé publique age, de sorte que rien de lisible ne touche le disque et que le
téléphone soit incapable de relire ce qu'il a produit.

Déchiffrement côté réception : le binaire `age`, rien d'autre.

```
age -d -i key.txt neruppu_motion_1760390000.jpg.age > capture.jpg
```

## Contenu

```
age-encryption/          module Go, binding gomobile autour de filippo.io/age
  age_writer.go          l'API exposée à Kotlin (3 fonctions)
  age_writer_test.go     tests de round-trip contre le vrai CLI age
  cmd/simulate-capture/  simulateur desktop du chemin saveImage
  BUILD.md               construction de l'AAR, surface du binding
android/
  crypto/AgeRecipientStore.kt       stockage de la recipient (clé publique)
  crypto/AgeEncryptor.kt            encrypt() + openStream(), OutputStream age
  storage/MediaStorageRepositoryImpl.kt   version modifiée (fichier complet)
  camera/CameraManager.capturePhotoBytes.kt  remplacement de capturePhoto()
  PATCHES.md             les 8 modifications restantes, en diffs
tools/
  neruppu-decrypt        wrapper confort autour de age -d
```

## État de vérification

| Élément | Statut |
|---|---|
| Module Go, compilation | **vérifié** (go build) |
| Round-trip age, chemin bytes | **vérifié** (5 tests, CLI age 1.1.1) |
| Round-trip age, chemin fd/stream | **vérifié**, écritures partielles comprises |
| Rejet clé privée / ssh / vide comme recipient | **vérifié** |
| Flux non finalisé indéchiffrable | **vérifié** (documenté comme mode de panne) |
| Démo bout-en-bout sur un vrai JPEG | **vérifié**, identique au bit près |
| `tools/neruppu-decrypt` | **vérifié**, y compris mauvaise clé et blob tronqué |
| Code Kotlin | **non compilé** — pas de SDK Android dans l'environnement |
| Binding gomobile réel | **non construit** — pas de NDK |
| Comportement sur appareil | **non testé** |

Le Kotlin est écrit contre les signatures réelles de Neruppu (HEAD `31785869`)
mais n'a jamais vu de compilateur. Attends-toi à des broutilles d'imports.

## Mesures relevées

Sur un JPEG 640×480 qualité 95, ce que produit le `ResolutionStrategy` actuel :

```
107 420 B en clair  ->  107 636 B chiffré   (+216 B, 0,20 %)
```

Le surcoût age est constant (en-tête + stanza X25519 + tags STREAM), donc
négligeable aux tailles en jeu. Les 107 Ko confirment l'estimation de 50–150 Ko
par photo faite plus tôt.

## Décisions de conception

**Fichier age pur.** Pas de magic number, pas d'empreintes de destinataires, pas
de conteneur. Cryptocam fait l'inverse — un en-tête en clair puis son propre
format de trames — ce qui l'oblige à distribuer un outil compagnon. Ici
`age -d` rend directement un JPEG ouvrable, avec n'importe quelle
implémentation (Go, rage, pyrage, une page WASM).

**Pas d'API de déchiffrement sur l'appareil.** Le module n'expose que
`EncryptBytes`, `NewWriterToFd` et `IsValidRecipient`. Absence délibérée.

**Pas de repli silencieux.** `AgeRecipientStore` ne retombe pas sur des
préférences en clair si le Keystore échoue, et la surveillance refuse de
démarrer sans recipient (PATCHES.md §3). C'est le défaut relevé dans
`MatrixConfigStore` lors de l'audit ; il ne faut pas le reproduire.

**Validation par la bibliothèque.** `IsValidRecipient` appelle
`age.ParseX25519Recipient` plutôt qu'une regex : ce qui est accepté dans les
réglages est exactement ce qui chiffrera plus tard.

## Ce que ça change dans l'app

La galerie locale disparaît : l'appareil n'a aucune clé privée, donc
`LogsScreen` ne peut plus afficher de vignettes. Métadonnées seulement
(horodatage, capteur, statut d'envoi). Pour un téléphone laissé dans une
chambre d'hôtel, c'est une amélioration — celui qui le trouve ne voit rien non
plus.

## Ce qui reste ouvert

1. **Audio** : `saveAudio` reçoit encore un `.mp4` en clair écrit par
   `MediaRecorder`, le chiffre et écrase l'original. Fenêtre de quelques
   secondes en stockage privé. Fermeture complète = `AudioRecord` + `MediaCodec`
   en trames ADTS dans `AgeEncryptor.openStream()` (PATCHES.md §8).
2. **Légende en clair** : `AlertMessageFormatter` part en clair à côté de chaque
   blob. Avec la charge chiffrée, c'est devenu la seule fuite. Voir §6.
3. **Base Room non chiffrée** : inchangé, et hors périmètre.
4. **Bug trouvé en chemin, indépendant du chiffrement** : `capturePhoto()` écrit
   un JPEG en clair que `MonitoringService` relit, puis `saveImage` en écrit un
   second — le premier n'est jamais supprimé. Chaque événement laisse une copie
   en clair orpheline dans `captures/`. Corrigé ici en capturant en mémoire.

## Démarrage

```sh
# Côté réception, une fois
age-keygen -o ~/.neruppu/key.txt      # colle la clé age1... dans les réglages

# Vérifier le module
cd age-encryption && go test ./...

# Simuler une capture sans appareil
go run ./cmd/simulate-capture -r age1... -in photo.jpg -out photo.jpg.age
age -d -i ~/.neruppu/key.txt photo.jpg.age > rendu.jpg
```
