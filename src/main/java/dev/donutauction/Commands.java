package dev.donutauction;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.donutauction.core.Auction;
import dev.donutauction.core.ChatParser;
import dev.donutauction.core.Money;
import dev.donutauction.gui.DashboardScreen;
import dev.donutauction.gui.ItemPickerScreen;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** Client-side commands. They are handled locally and never sent to the server. */
final class Commands {
    private Commands() {}

    static void register(CommandDispatcher<FabricClientCommandSource> d) {
        d.register(literal("dauction")
                .executes(c -> {
                    DonutAuctionClient.openDashboard();
                    return 1;
                })
                .then(literal("new").executes(c -> {
                    DonutAuctionClient.openLater(() -> new ItemPickerScreen(new DashboardScreen()));
                    return 1;
                }))
                .then(literal("status").executes(c -> {
                    var running = DonutAuctionClient.engine().running();
                    if (running.isEmpty()) DonutAuctionClient.say("No running auctions.", 0xAAAAAA);
                    long now = System.currentTimeMillis();
                    for (Auction a : running) {
                        var h = a.highest();
                        DonutAuctionClient.say("#" + a.id + " " + a.item.summary() + " | "
                                + (h == null ? "no bids" : Money.format(h.amount) + " by " + h.bidder)
                                + " | " + dev.donutauction.core.Fmt.duration(a.remainingMs(now)) + " left", 0xFFFFFF);
                    }
                    return 1;
                }))
                .then(literal("debug")
                        .then(literal("on").executes(c -> setDebug(true)))
                        .then(literal("off").executes(c -> setDebug(false))))
                .then(literal("sim").then(argument("line", StringArgumentType.greedyString()).executes(c -> {
                    DonutAuctionClient.simulate(StringArgumentType.getString(c, "line"));
                    return 1;
                })))
                .then(literal("parse").then(argument("line", StringArgumentType.greedyString()).executes(c -> {
                    String line = StringArgumentType.getString(c, "line");
                    ChatParser p = DonutAuctionClient.parser();
                    ChatParser.ParsedBid b = p.parseBid(line);
                    ChatParser.ParsedPayment pay = p.parsePayment(line);
                    DonutAuctionClient.say("bid: " + (b == null ? "no match" : "user=" + b.user + " amount=" + b.amountText
                            + " (" + (b.amount > 0 ? Money.format(b.amount) : "INVALID") + ") id=" + b.auctionId), 0xFFFFFF);
                    DonutAuctionClient.say("payment: " + (pay == null ? "no match" : "user=" + pay.user + " amount=" + pay.amountText
                            + " (" + (pay.amount > 0 ? Money.format(pay.amount) : "INVALID") + ")"), 0xFFFFFF);
                    return 1;
                })))
                .then(literal("block").then(argument("name", StringArgumentType.word()).executes(c -> {
                    String n = StringArgumentType.getString(c, "name");
                    DonutAuctionClient.say(DonutAuctionClient.engine().block(n) ? n + " blocked from bidding."
                            : "Could not block '" + n + "' (invalid name or already blocked).", 0xFFAA00);
                    return 1;
                })))
                .then(literal("unblock").then(argument("name", StringArgumentType.word()).executes(c -> {
                    String n = StringArgumentType.getString(c, "name");
                    DonutAuctionClient.say(DonutAuctionClient.engine().unblock(n) ? n + " unblocked." : n + " was not blocked.", 0xAAAAAA);
                    return 1;
                })))
                .then(literal("blocked").executes(c -> {
                    var list = DonutAuctionClient.engine().blockedList();
                    DonutAuctionClient.say(list.isEmpty() ? "Blocklist is empty." : "Blocked: " + String.join(", ", list), 0xAAAAAA);
                    return 1;
                })));
    }

    private static int setDebug(boolean on) {
        DonutAuctionClient.settings().debug = on;
        DonutAuctionClient.say("Debug " + (on ? "ON: lines containing 'bid' or 'paid' are classified in chat." : "OFF."), 0xAAAAAA);
        return 1;
    }
}
