package com.example.testplugin.service

import priv.seventeen.artist.asteroid.AsteroidAPI
import priv.seventeen.artist.asteroid.item.ItemTag
import priv.seventeen.artist.asteroid.item.ItemTagData
import priv.seventeen.artist.blink.bukkitPlugin
import priv.seventeen.artist.blink.nms.AsteroidManager
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

object NMSBridge {

    val isAvailable: Boolean get() = AsteroidManager.isAvailable

    fun init() {
        if (!AsteroidManager.isAvailable) {
            bukkitPlugin.logger.warning("[NMS] Asteroid not available")
            return
        }

        bukkitPlugin.logger.info("[NMS] Asteroid ready — MC ${AsteroidAPI.getMcVersion()}")
    }

    /**
     * 给物品写入自定义 NBT/DataComponent 标签（跨版本）
     */
    fun tagItem(item: ItemStack, key: String, value: Int): ItemStack {
        if (!isAvailable) return item

        val tag = ItemTag.fromItemStack(item)
        tag.putDeep("blink.$key", ItemTagData.of(value))
        tag.saveTo(item)
        return item
    }

    /**
     * 读取物品的自定义标签
     */
    fun readTag(item: ItemStack, key: String): Int? {
        if (!isAvailable) return null

        val tag = ItemTag.fromItemStack(item)
        return tag.getDeep("blink.$key")?.asInt()
    }

    /**
     * 物品序列化为 JSON（跨版本）
     */
    fun itemToJson(item: ItemStack): String? {
        if (!isAvailable) return null
        return AsteroidAPI.getItemStackNMS().item2Json(item)
    }

    /**
     * JSON 反序列化为物品（跨版本）
     */
    fun jsonToItem(json: String): ItemStack? {
        if (!isAvailable) return null
        return AsteroidAPI.getItemStackNMS().json2Item(json)
    }

    /**
     * 设置玩家最大生命值修饰符
     */
    fun setMaxHealth(player: Player, amount: Double) {
        if (!isAvailable) return

        val bridge = AsteroidAPI.getAttributeBridge()
        bridge.setModifier(
            player,
            "generic.max_health",
            "blink_test_health",
            amount,
            0 // ADD_NUMBER
        )
    }
}
