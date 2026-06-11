package com.justingoat.goat.client.events.impl.hypixel;

import com.justingoat.goat.client.events.AbstractEvent;
import net.azureaaron.hmapi.data.rank.MonthlyPackageRank;
import net.azureaaron.hmapi.data.rank.PackageRank;
import net.azureaaron.hmapi.data.rank.PlayerRank;

import java.util.Optional;

public class PlayerInfoPacketEvent extends AbstractEvent {
    private final PlayerRank playerRank;
    private final PackageRank packageRank;
    private final MonthlyPackageRank monthlyPackageRank;
    private final Optional<String> prefix;

    public PlayerInfoPacketEvent(PlayerRank playerRank, PackageRank packageRank,
                                 MonthlyPackageRank monthlyPackageRank, Optional<String> prefix) {
        this.playerRank = playerRank;
        this.packageRank = packageRank;
        this.monthlyPackageRank = monthlyPackageRank;
        this.prefix = prefix;
    }

    public PlayerRank getPlayerRank() { return playerRank; }
    public PackageRank getPackageRank() { return packageRank; }
    public MonthlyPackageRank getMonthlyPackageRank() { return monthlyPackageRank; }
    public Optional<String> getPrefix() { return prefix; }
}
