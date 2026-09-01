**All up-to-date information about ColorLight is stored on [Modrinth]([https://modrinth.com/mod/colorlight]%28https://modrinth.com/mod/colorlight%29); this page only contains notes that may clarify some aspects of the mod, explaining what will or will not be included in the mod.**

ColorLight is a mod that automatically determines how a block should glow and generates colored lighting based on that.

<details>
<summary>Versions</summary>

| Version                                                                                                                                                                                                    | Further support |
| :--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | :-------------: |
| 1.20.x                                                                                                                                                                                                     |        No       |
| 1.21.x                                                                                                                                                                                                     |        No       |
| 1.21.11                                                                                                                                                                                                    |       Yes       |
| 26.x                                                                                                                                                                                                       |       Yes       |
| Older Minecraft versions are personally difficult for me to maintain, as sometimes I have to completely rewrite all the added code. ColorLight versions for 1.21.1 will therefore remain at 0.2.0 forever. |                 |

</details>

<details>
<summary>Other mods</summary>

Mods that simply add a light-emitting block through vanilla registries are supported, but if a mod takes a different approach, ColorLight may simply skip it.

Many optimization mods are supported because the mod only colors the block rather than changing vanilla lighting.

If a mod adds features that differ from vanilla Minecraft, a separate compatibility layer will have to be written for it. The following are such mods:

| Mod                 |     Support    |                                     How it works                                    |
| :------------------ | :------------: | :---------------------------------------------------------------------------------: |
| - LambDynamicLights |       Yes      |               A lighting marker is placed at the entity's coordinates               |
| - Voxy              |  Experimental  | When a chunk is loaded, a square is placed in the color of the light-emitting block |
| - Distant Horizon   | In development |                              Currently being developed                              |

</details>

<details>
<summary>Known issues</summary>

| Issue description                                                                  |                                          Why it happens                                         |                                                   How to fix                                                  |
| :--------------------------------------------------------------------------------- | :---------------------------------------------------------------------------------------------: | :-----------------------------------------------------------------------------------------------------------: |
| "Description: Mod 'colorlight' failed while registering config options." with RRLS |   This happens because ColorLight does not have enough time to create its settings due to RRLS  | [Fix]([https://github.com/MrHikmen/ColorLight/issues/7]%28https://github.com/MrHikmen/ColorLight/issues/7%29) |
| ColorLight settings are not applied                                                |                    The settings application was poorly implemented initially                    |                                        Wait for a fix in future updates                                       |
| Strange triangles on version 1.21.11 or squares on version 26.2                    |                           This is an old version of Voxy compatibility                          |                      Disable "Enable Voxy compatibility" in the "Compatibility" settings                      |
| Crashes when "Compute lighting on GPU" is enabled                                  | Your system or device is not compatible with OpenGL 4.3, and ColorLight was unable to detect it |                     Disable "Compute lighting on GPU" or wait for a fix in future updates                     |

</details>

<details>
<summary>Rus</summary>

**Вся актуальная информация о ColorLight хранится на [modrint](https://modrinth.com/mod/colorlight), здесь лишь записи, которые могут прояснить некоторые моменты мода, сообшающие о том, что будет или не будет в моде.**

ColorLight is a mod that automatically determines how a block should glow and generates colored lighting based on that.

<details>
<summary>Версии</summary>

| Версия  | Дальнейшая поддержка |
|:--------|:--------------------:|
| 1.20.x  |       Не будет       |
| 1.21.x  |       Не будет       |
| 1.21.11 |        Будет         |
| 26.x    |        Будет         |
Старые версии майнкрафта мне лично трудно поддерживать, так как иногда приходиться полнустью переписывать весь добавленный код. Версии ColorLight для 1.21.1 так и остануться навсегда 0.2.0
</details>

<details>
<summary>Другие моды</summary>

Поддержка модов которые просто добавляют светящийся блок через ванильные реестры поддерживаются, но если мод пошёл другим путём ColorLight его может просто пропустить.

Многие моды на оптимизацию поддерживаются из-за того, что мод просто красит блок, а не меняет ванильное освещение.

Если мод добавляет специфические особенности отличные от ванильного Майнкрафта, то под него придётся писать отдельный слой совместимостей. Вот подобные моды:

| Мод                 |    Поддержка     |                         Как работает                          |
|:--------------------|:----------------:|:-------------------------------------------------------------:|
| - LambDynamicLights |       Есть       |        Метка освещение ставиться на координатах энтити        |
| - Voxy              | Экспериментально | При загрузки чанка ставиться квадрад в цвет светящегося блока |
| - Distant Horizon   |   В разработке   |                     Пока разрабатывается                      |
</details>

<details>
<summary>Известные ошибки</summary>

| Описание ошибки                                                                 |                                  Из-за чего происходит                                  |                                   Как исправить                                   |
|:--------------------------------------------------------------------------------|:---------------------------------------------------------------------------------------:|:---------------------------------------------------------------------------------:|
| "Description: Mod 'colorlight' failed while registering config options." с RRLS |   Происходит из-за того что ColorLight не успевает создать свои настройки из-за RRLS    |          [Исправление](https://github.com/MrHikmen/ColorLight/issues/7)           |
| Не применяются настройки ColorLight                                             |                     Изначально криво написанное применение настроек                     |                     Дождаться исправление в новых обновлениях                     |
| Непонятные треугольники на версии 1.21.11 или квадраты на версии 26.2           |                         Это старая версия совместимости с Voxy                          |        Отключить "Enable Voxy compatibility" в настройках "Compatibility"         |
| Вылеты с включением "Compute lighting on GPU"                                   | Ваша система или устройство не совместимо с OpenGL 4.3, а ColorLight не смог это понять | Выключить "Compute lighting on GPU" или дождаться исправление в новых обновлениях |
</details>

</details>