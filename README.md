# Ma Moyenne

Application Android pour calculer la moyenne de ses notes selon l'échelle suisse (1 à 6) et le barème fédéral.

**Télécharger l'APK :** [`release/MaMoyenne.apk`](release/MaMoyenne.apk)

## Fonctions

- Branches avec coefficient, notes avec pondération (ex. un examen qui compte double).
- Saisie d'une note directement, ou en points avec le barème fédéral :
  **note = points obtenus ÷ points max × 5 + 1**.
- Moyenne de chaque branche, arrondie au demi-point par défaut (réglable : 0.5 / 0.25 / 0.1 / aucun).
- Moyenne générale = moyenne des branches arrondies, pondérée par les coefficients.
- Points positifs / négatifs et contrôle de la compensation (points positifs ≥ 2 × points négatifs).
- « Note nécessaire » : quelle note viser au prochain test pour atteindre un objectif.
- Données enregistrées sur le téléphone uniquement.

## Installation

1. Ouvre `release/MaMoyenne.apk` depuis ton téléphone et télécharge-le.
2. Autorise l'installation d'applications de sources inconnues si Android le demande.
3. Installe.

## Compiler

```
./gradlew test assembleRelease
```

L'APK est produit dans `app/build/outputs/apk/release/`. Le workflow GitHub Actions `Build APK` le construit aussi à chaque push.
