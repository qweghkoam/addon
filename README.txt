44444 dev — Meteor addon, порт на Minecraft 1.21.4 (Meteor 1.21.4 build 42, Baritone API 1.13.1)

Сборка (нужен JDK 21 и интернет):
  gradle wrapper --gradle-version 8.12     # один раз, если нет gradlew
  ./gradlew build                          # Windows: gradlew.bat build

Готовый jar: build/libs/44444-dev-1.7.4-mc1.21.4.jar
(имя меняется в gradle.properties -> jar_name)

Положить в mods вместе с Meteor Client 1.21.4 и Baritone (baritone-meteor) для 1.21.4.
