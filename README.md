# MasquageBullesManga

Banc d'essai Android indépendant pour évaluer la détection des bulles avant toute intégration dans MangaLensFR.

## Version 0.2.0 — segmentation ONNX

- Choix d'une page locale JPG/PNG.
- Téléchargement unique du modèle ONNX (environ 12 Mo) dans le stockage privé de l'application.
- Segmentation exécutée sur le téléphone via ONNX Runtime.
- Masquage blanc 100 % opaque et comparaison visuelle avec l'original.
- Aucune image envoyée sur un serveur, aucun OCR, aucune traduction.

Modèle : [manga109-segmentation-bubble-onnx](https://huggingface.co/mednasserallah/manga109-segmentation-bubble-onnx), export ONNX du modèle YOLO11-nano de segmentation de bulles. Licence des poids : Apache-2.0. Le premier lancement nécessite Internet pour télécharger le fichier du modèle ; les lancements suivants réutilisent le fichier local.

## Limites à vérifier

Le modèle est entraîné principalement sur des mangas japonais/anglais. Il peut manquer les bulles sombres, les trames complexes ou des formes inhabituelles, et peut masquer des zones d'illustration. La vitesse publiée par l'auteur du modèle n'est pas une mesure sur tous les téléphones Android. Cette app est un banc d'essai : ne pas intégrer à MangaLensFR avant validation visuelle sur plusieurs pages.

## Build

GitHub Actions construit l'APK debug à chaque push.
