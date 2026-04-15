/*
 * Copyright 2026 17Artist
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package priv.seventeen.artist.blink.command

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import priv.seventeen.artist.blink.BlinkLog

class BlinkCommand(name: String, vararg aliases: String)
    : Command(name, "", "/$name", aliases.toList()) {

    internal val groups = linkedMapOf<String, BlinkCommandGroup>()
    private val groupNameMap = hashMapOf<String, String>()
    internal val rootCommands = linkedMapOf<String, ResolvedCommand>()
    private val rootNameMap = hashMapOf<String, String>()
    private val tabProviders = hashMapOf<String, () -> Collection<String>>()

    fun group(g: BlinkCommandGroup): BlinkCommand {
        groups[g.groupName] = g; groupNameMap[g.groupName.lowercase()] = g.groupName; return this
    }
    fun sub(executor: BlinkCommandGroup): BlinkCommand {
        for ((n, c) in executor.commands) { rootCommands[n] = c; rootNameMap[n.lowercase()] = n }; return this
    }

    fun command(
        name: String,
        description: String = "",
        permission: String = "",
        args: Array<String> = emptyArray(),
        sender: SenderType = SenderType.ALL,
        handler: (CommandContext) -> Unit
    ): BlinkCommand {
        val resolved = ResolvedCommand(name, description, permission, args, sender, null, this, handler)
        rootCommands[name] = resolved
        rootNameMap[name.lowercase()] = name
        return this
    }

    fun tabComplete(argName: String, provider: () -> Collection<String>): BlinkCommand {
        tabProviders[argName.lowercase()] = provider; return this
    }

    override fun execute(sender: CommandSender, label: String, args: Array<String>): Boolean {
        if (args.isEmpty()) { sendHelp(sender, label); return true }
        val sub = args[0]
        rootCommands[rootNameMap[sub.lowercase()]]?.let { return exec(sender, label, it, args.copyOfRange(1, args.size)) }
        val group = groups[groupNameMap[sub.lowercase()]]
        if (group == null) { sender.sendMessage("§c未知命令: /$label $sub"); return true }
        if (args.size < 2) { sendGroupHelp(sender, label, sub, group); return true }
        val resolved = group.resolve(args[1]) ?: run { sender.sendMessage("§c未知子命令: /$label $sub ${args[1]}"); return true }
        return exec(sender, label, resolved, args.copyOfRange(2, args.size))
    }

    private fun exec(sender: CommandSender, label: String, cmd: ResolvedCommand, args: Array<String>): Boolean {
        if (!checkSender(sender, cmd)) return true
        if (cmd.permission.isNotEmpty() && !sender.hasPermission(cmd.permission)) {
            sender.sendMessage("§c无权限 §7(${cmd.permission})"); return true
        }
        if (args.size < cmd.requiredArgs) {
            sender.sendMessage("§c参数不足: ${cmd.args.joinToString(" ") { if (it.startsWith("?")) "§7[${it.substring(1)}]" else "§6<$it>" }}"); return true
        }
        try {
            cmd.execute(CommandContext(sender, label, args))
        } catch (e: Throwable) {
            sender.sendMessage("§c命令执行出错")
            BlinkLog.error("/$label 出错", e)
        }
        return true
    }

    private fun checkSender(sender: CommandSender, cmd: ResolvedCommand): Boolean = when (cmd.sender) {
        SenderType.ALL -> true
        SenderType.PLAYER -> if (sender !is Player) { sender.sendMessage("§c仅限玩家"); false } else true
        SenderType.OP -> if (!sender.isOp) { sender.sendMessage("§c仅限 OP"); false } else true
        SenderType.CONSOLE -> if (sender !is ConsoleCommandSender) { sender.sendMessage("§c仅限控制台"); false } else true
    }

    override fun tabComplete(sender: CommandSender, alias: String, args: Array<String>): List<String> {
        if (args.size == 1) return filter(groups.keys + rootCommands.keys, args[0])
        val sub = args[0]
        rootCommands[rootNameMap[sub.lowercase()]]?.let { return completeArgs(it, args, 1) }
        val group = groups[groupNameMap[sub.lowercase()]] ?: return emptyList()
        if (args.size == 2) return filter(group.commands.keys, args[1])
        val resolved = group.resolve(args[1]) ?: return emptyList()
        return completeArgs(resolved, args, 2)
    }

    private fun completeArgs(cmd: ResolvedCommand, args: Array<String>, offset: Int): List<String> {
        val idx = args.size - offset - 1
        if (idx < 0 || idx >= cmd.args.size) return emptyList()
        val argName = cmd.args[idx].removePrefix("?").lowercase()
        if (argName == "player") return filter(Bukkit.getOnlinePlayers().map { it.name }.toSet(), args.last())
        return tabProviders[argName]?.let { filter(it().toSet(), args.last()) } ?: emptyList()
    }

    private fun filter(c: Collection<String>, prefix: String): List<String> {
        val l = prefix.lowercase(); return c.filter { it.lowercase().startsWith(l) }
    }

    private fun sendHelp(s: CommandSender, l: String) {
        s.sendMessage("§7/$l §f命令列表:")
        groups.forEach { (n, g) -> s.sendMessage("  §f/$l $n §7- ${g.groupDescription}") }
        rootCommands.forEach { (n, c) -> s.sendMessage("  §f/$l $n §7- ${c.description}") }
    }
    private fun sendGroupHelp(s: CommandSender, l: String, sub: String, g: BlinkCommandGroup) {
        s.sendMessage("§7/$l $sub §f命令列表:")
        g.commands.forEach { (n, c) ->
            val ah = c.args.joinToString(" ") { if (it.startsWith("?")) "§7[${it.substring(1)}]" else "§7<§f$it§7>" }
            s.sendMessage("  §f/$l $sub $n $ah §7- ${c.description}")
        }
    }
}
