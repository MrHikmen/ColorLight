# ColorLight

ColorLight is an attempt to bring colored lighting support to a huge variety of mods and resource packs without relying on a predefined list of light-emitting blocks, while still allowing that list to be customized if desired.

### How it works

When a light-emitting block is detected, the mod:

* Finds the block's texture.
* Analyzes the texture.
* Extracts the dominant color.
* Uses that color for the block's light.

### Configuration

Most of the mod's settings can be configured through **Sodium**.

Light colors can be customized using:

* Sodium settings
* Resource packs
* Commands

### Fun Tab

The mod includes a separate **"Fun"** tab designed for experimenting with lighting effects. For example, you can:

* Adjust the pixel scanner balance.
* Increase the light radius.
* Change light intensity.
* And more.


<details>
<summary>Commands</summary>

**Get information about the light color of the block you're looking at**

```text
/colorlight info
```

**Get information about the light color of a specific block**

```text
/colorlight info_block <block>
```

**Change the light color of a specific block**

```text
/colorlight repaint <block> <red> <green> <blue> <light_strength>
```

**Restore the default light color of a specific block**

```text
/colorlight repaint_return <block>
```

**Change the light color of the block you're looking at**

```text
/colorlight repaint_block <red> <green> <blue> <light_strength>
```

**Restore (or remove) the custom light color of the block you're looking at**

```text
/colorlight repaint_block_return
```

**Add colored light to any block you're looking at**

```text
/colorlight add_light <red> <green> <blue> <light_strength> <save_source_position>
```

**Remove colored light from any block you're looking at**

```text
/colorlight remove_light
```

**Create a customizable glowing light block**

```text
/colorlight add_light_block <red> <green> <blue> <block_size> <light_strength> <save_source_position>
```

**Remove a customizable glowing light block you're looking at**

```text
/colorlight remove_light_block
```

</details>


<details>
<summary>Rus</summary>


# ColorLight

ColorLight — это попытка реализовать цветное освещение для огромного количества модов и ресурспаков без использования заранее заданного списка светящихся блоков, при этом сохранив возможность настраивать этот список вручную.

## Как это работает

При обнаружении светящегося блока мод:

* Находит текстуру блока.
* Анализирует её.
* Определяет преобладающий цвет.
* Использует этот цвет для освечения блока.

## Настройка

Большинство параметров мода можно настроить через **Sodium**.

Цвета освещения можно изменять с помощью:

* настроек Sodium;
* ресурспаков;
* команд.

## Вкладка «Fun»

В моде есть отдельная вкладка **«Fun»**, предназначенная для экспериментов с освещением. Например, она позволяет:

* изменять баланс сканирования пикселей;
* увеличивать радиус свечения;
* настраивать интенсивность освещения;
* и многое другое.


<details>
<summary>Команды</summary>

**Узнать информацию о свечении блока, на который вы смотрите**

```text
/colorlight info
```

**Узнать информацию о свечении указанного блока**

```text
/colorlight info_block <блок>
```

**Изменить цвет свечения указанного блока**

```text
/colorlight repaint <блок> <red> <green> <blue> <сила_свечения>
```

**Вернуть цвет свечения указанного блока по умолчанию**

```text
/colorlight repaint_return <блок>
```

**Изменить цвет свечения блока, на который вы смотрите**

```text
/colorlight repaint_block <red> <green> <blue> <сила_свечения>
```

**Вернуть (или удалить) пользовательский цвет свечения блока, на который вы смотрите**

```text
/colorlight repaint_block_return
```

**Добавить цветное свечение любому блоку, на который вы смотрите**

```text
/colorlight add_light <red> <green> <blue> <сила_свечения> <сохранить_позицию_источника>
```

**Убрать свечение у любого блока, на который вы смотрите**

```text
/colorlight remove_light
```

**Создать настраиваемый светящийся блок**

```text
/colorlight add_light_block <red> <green> <blue> <размер_блока> <сила_свечения> <сохранить_позицию_источника>
```

**Удалить настраиваемый светящийся блок, на который вы смотрите**

```text
/colorlight remove_light_block
```

</details>
</details>


