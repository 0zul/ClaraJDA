package com.avairebot.scheduler;

import com.avairebot.AvaIre;
import com.avairebot.commands.system.SetStatusCommand;
import com.avairebot.contracts.scheduler.Job;
import com.avairebot.utilities.NumberUtil;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.entities.Game;

import java.util.Arrays;

public class ChangeGameJob extends Job {

    private int index = 0;

    public ChangeGameJob(AvaIre avaire) {
        super(avaire, 1);
    }

    @Override
    public void run() {
        if (!avaire.areWeReadyYet()) {
            return;
        }

        if (SetStatusCommand.HAS_CUSTOM_STATUS) {
            return;
        }

        if (index >= avaire.getConfig().getStringList("playing").size()) {
            index = 0;
        }

        for (JDA shard : avaire.getShardManager().getShards()) {
            shard.getPresence().setGame(
                getGameFromType(avaire.getConfig().getStringList("playing").get(index), shard)
            );
        }
        index++;
    }

    private Game getGameFromType(String status, JDA shard) {
        Game game = Game.playing(status);
        if (status.contains(":")) {
            String[] split = status.split(":");
            status = String.join(":", Arrays.copyOfRange(split, 1, split.length));
            switch (split[0].toLowerCase()) {
                case "listen":
                case "listening":
                    return Game.listening(formatGame(status, shard));

                case "watch":
                case "watching":
                    return Game.watching(formatGame(status, shard));

                case "play":
                case "playing":
                    return Game.playing(formatGame(status, shard));

                case "stream":
                case "streaming":
                    return Game.streaming(formatGame(status, shard), "https://www.twitch.tv/senither");
            }
        }
        return game;
    }

    private String formatGame(String game, JDA shard) {
        game = game.replaceAll("%users%", NumberUtil.formatNicely(avaire.getShardEntityCounter().getUsers()));
        game = game.replaceAll("%guilds%", NumberUtil.formatNicely(avaire.getShardEntityCounter().getGuilds()));

        game = game.replaceAll("%shard%", shard.getShardInfo().getShardString());
        game = game.replaceAll("%shard-id%", "" + shard.getShardInfo().getShardId());
        game = game.replaceAll("%shard-total%", "" + shard.getShardInfo().getShardTotal());

        return game;
    }
}
