/*
 * Copyright (c) 2018.
 *
 * This file is part of AvaIre.
 *
 * AvaIre is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AvaIre is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AvaIre.  If not, see <https://www.gnu.org/licenses/>.
 *
 *
 */

package com.avairebot.level;

import com.avairebot.AvaIre;
import com.avairebot.chat.MessageType;
import com.avairebot.chat.PlaceholderMessage;
import com.avairebot.database.controllers.GuildController;
import com.avairebot.database.controllers.PlayerController;
import com.avairebot.database.transformers.GuildTransformer;
import com.avairebot.database.transformers.PlayerTransformer;
import com.avairebot.factories.MessageFactory;
import com.avairebot.language.I18n;
import com.avairebot.utilities.CacheUtil;
import com.avairebot.utilities.RandomUtil;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import net.dv8tion.jda.core.entities.Message;
import net.dv8tion.jda.core.entities.Role;
import net.dv8tion.jda.core.entities.TextChannel;
import net.dv8tion.jda.core.entities.User;
import net.dv8tion.jda.core.events.message.MessageReceivedEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@SuppressWarnings({"WeakerAccess", "unused"})
public class LevelManager {

    /**
     * When a user sends a message, they are checked against the cache to see if they
     * can be rewarded experience again, if they do have an entry in the cache,
     * their message is ignored for the level manager and no experience will
     * be rewarded to them for that message.
     * <p>
     * The cache will automatically delete keys after they have existed for 60 seconds.
     */
    public static final Cache<Object, Object> cache = CacheBuilder.newBuilder()
        .recordStats()
        .expireAfterWrite(60, TimeUnit.SECONDS)
        .build();

    /**
     * The experience queue, users who have been rewarded experience will
     * be added to the queue, the queue is then consumed once a minute
     * to sync the database with the user data.
     */
    private static final List<ExperienceEntity> experienceQueue = new CopyOnWriteArrayList<>();

    /**
     * The experience modifier as an percentage.
     */
    private static final double M = 0.3715D;

    /**
     * The quadratic equation `a` value.
     */
    private final int A = 5;
    /**
     * The quadratic equation `b` value.
     */
    private final int B = 50;
    /**
     * The quadratic equation `c` value.
     */
    private final int C = 100;

    /**
     * Gets the default level experience modifier.
     *
     * @return The default level experience modifier.
     */
    public static double getDefaultModifier() {
        return M;
    }

    /**
     * Get the amount of experience needed to reach the given level.
     *
     * @param level The level the experience should be fetched from.
     * @return The minimum amount of experience needed to reach the given level.
     */
    public long getExperienceFromLevel(long level) {
        return getExperienceFromLevel(level, M);
    }

    /**
     * Get the amount of experience needed to reach the given level
     * using the guilds custom modifier if one is set, otherwise
     * the default {@link #M modifier} will be used.
     *
     * @param transformer The guild transformer object that the level modifier should be loaded from.
     * @param level       The level the experience
     * @return The minimum amount of experience needed to reach the given level.
     */
    public long getExperienceFromLevel(@Nullable GuildTransformer transformer, long level) {
        if (transformer == null || transformer.getLevelModifier() < 0) {
            return getExperienceFromLevel(level, M);
        }
        return getExperienceFromLevel(level, transformer.getLevelModifier());
    }

    /**
     * Get the amount of experience needed to reach the given level.
     *
     * @param level    The level the experience should be fetched from.
     * @param modifier The modifier that should be added to the XP calculation, the modifier
     *                 should be a percentage represented as a decimal, so 0.5 = 50%
     * @return The minimum amount of experience needed to reach the given level.
     */
    public long getExperienceFromLevel(long level, double modifier) {
        return (long) (((long) (A * Math.pow(level, 2)) + (B * level) + (C * (1 + modifier))) * (1 + modifier));
    }

    /**
     * Gets the max level that can be reached with the given amount experience.
     *
     * @param xp The experience that should be resolved into the level.
     * @return The max level that can be reached with the given amount of experience.
     */
    public long getLevelFromExperience(long xp) {
        return getLevelFromExperience(xp, M);
    }

    /**
     * Gets the max level that can be reached with the given amount of
     * experience using the guilds custom modifier if one is set,
     * otherwise the default {@link #M modifier} will be used.
     *
     * @param transformer The guild transformer object that the level modifier should be loaded from.
     * @param xp          The experience that should be resolved into the level.
     * @return The max level that can be reached with the given amount of experience.
     */
    public long getLevelFromExperience(@Nullable GuildTransformer transformer, long xp) {
        if (transformer == null || transformer.getLevelModifier() < 0) {
            return getLevelFromExperience(xp, M);
        }
        return getLevelFromExperience(xp, transformer.getLevelModifier());
    }

    /**
     * Gets the max level that can be reached with the given amount experience.
     *
     * @param xp       The experience that should be resolved into the level.
     * @param modifier The modifier that should be subtracted from the XP calculation, the modifier
     *                 should be a percentage represented as a decimal, so 0.5 = 50%
     * @return The max level that can be reached with the given amount of experience.
     */
    public long getLevelFromExperience(long xp, double modifier) {
        double x = (-B + Math.sqrt(Math.pow(B, 2) - ((4 * A) * ((C * (1 + modifier)) - Math.ceil(xp / (1 + modifier)))))) / (2 * A);

        return x < 0 ? 0 : (long) Math.floor(x);
    }

    /**
     * Reward the player a random amount of experience between 10 and 15 using the
     * {@link MessageReceivedEvent}, the reward is throttled to one use every
     * minute per-guild-player, if the player has received experience in the
     * same guild through this method before in the last minute, nothing
     * will be given to the player/user.
     *
     * @param event  The event that should be used in rewarding the player.
     * @param guild  The guild transformer from the current guild database instance.
     * @param player The player transformer from the current player database instance.
     */
    public void rewardPlayer(MessageReceivedEvent event, GuildTransformer guild, PlayerTransformer player) {
        CacheUtil.getUncheckedUnwrapped(cache, asKey(event), () -> {
            giveExperience(event.getMessage(), guild, player);
            return 0;
        });
    }

    /**
     * Give the user the given amount of experience, updating the database and
     * saving it to the transformer, storing it temporarily in memory, if the
     * event is not a guild message event the method call will be canceled.
     *
     * @param avaire  The AvaIre application instance.
     * @param message The guild message event that should be used.
     * @param user    The user that should be given the experience.
     * @param amount  The amount of experience that should be given to the user.
     */
    public void giveExperience(AvaIre avaire, Message message, User user, int amount) {
        if (!message.getChannelType().isGuild()) {
            return;
        }

        giveExperience(message, GuildController.fetchGuild(avaire, message), PlayerController.fetchPlayer(avaire, message, user), amount);
    }

    /**
     * Give given player a random amount of experience between
     * 10 and 15, updating the database and saving it to the
     * transformer, storing it temporarily in memory.
     *
     * @param message The guild message event that should be used.
     * @param guild   The guild transformer for the guild the player is from.
     * @param player  The player that should be given the experience.
     */
    public void giveExperience(Message message, GuildTransformer guild, PlayerTransformer player) {
        giveExperience(message, guild, player, (RandomUtil.getInteger(5) + 10));
    }

    /**
     * Give the user the given amount of experience, updating the database and
     * saving it to the transformer, storing it temporarily in memory.
     *
     * @param message The guild message event that should be used.
     * @param guild   The guild transformer for the guild the player is from.
     * @param player  The player that should be given the experience.
     * @param amount  The amount of experience that should be given to the player.
     */
    public void giveExperience(Message message, GuildTransformer guild, PlayerTransformer player, int amount) {
        long exp = player.getExperience();
        long zxp = getExperienceFromLevel(guild, 0) - 100;
        long lvl = getLevelFromExperience(guild, exp + zxp);

        player.incrementExperienceBy(amount);

        experienceQueue.add(new ExperienceEntity(
            message.getAuthor().getIdLong(),
            message.getGuild().getIdLong(),
            amount
        ));

        if (getLevelFromExperience(guild, player.getExperience() + zxp) > lvl) {
            long newLevel = getLevelFromExperience(guild, player.getExperience() + zxp);

            if (guild.isLevelAlerts()) {
                boolean hasLevelupRole = !guild.getLevelRoles().isEmpty() && guild.getLevelRoles().containsKey((int) newLevel);

                PlaceholderMessage alertMessage = MessageFactory.makeEmbeddedMessage(getLevelUpChannel(message, guild))
                    .setColor(MessageType.SUCCESS.getColor())
                    .setDescription(loadRandomLevelupMessage(guild, hasLevelupRole))
                    .set("user", message.getAuthor().getAsMention())
                    .set("level", newLevel);

                if (hasLevelupRole) {
                    Role levelRole = message.getGuild().getRoleById(guild.getLevelRoles().get((int) newLevel));

                    if (levelRole == null) {
                        alertMessage.setDescription(loadRandomLevelupMessage(guild, false));
                    } else {
                        alertMessage.set("role", levelRole.getName());
                    }
                }

                alertMessage.queue();
            }

            if (!guild.getLevelRoles().isEmpty()) {
                List<Role> roles = getRoleRewards(message, guild, newLevel);
                if (roles.isEmpty()) {
                    return;
                }

                message.getGuild().getController().addRolesToMember(message.getMember(), roles).queue(aVoid -> {
                    if (!guild.isLevelHierarchy()) {
                        return;
                    }

                    boolean reachedUserLevel = false;
                    List<Role> rolesToRemove = new ArrayList<>();
                    Role lastGivenRole = null;

                    for (Map.Entry<Integer, String> entry : guild.getLevelRoles().entrySet()) {
                        if (entry.getKey() >= newLevel) {
                            reachedUserLevel = true;
                        }

                        if (entry.getKey() == newLevel || !reachedUserLevel) {
                            Role role = message.getGuild().getRoleById(entry.getValue());
                            if (role != null) {
                                lastGivenRole = role;
                            }
                        }

                        if (entry.getKey() <= newLevel) {
                            Role role = message.getGuild().getRoleById(entry.getValue());
                            if (role != null) {
                                rolesToRemove.add(role);
                            }
                        }
                    }

                    if (lastGivenRole == null || rolesToRemove.isEmpty()) {
                        return;
                    }

                    rolesToRemove.remove(lastGivenRole);

                    if (!rolesToRemove.isEmpty()) {
                        message.getGuild().getController().removeRolesFromMember(message.getMember(), rolesToRemove).queue();
                    }
                });
            }
        }
    }

    /**
     * Gets the experience queue, any user who has received experience and
     * have yet to be updated in the database are stored in this queue.
     *
     * @return The experience queue.
     */
    public List<ExperienceEntity> getExperienceQueue() {
        return experienceQueue;
    }

    /**
     * Gets all the experience entities that belongs to the given player
     * transformer, or an empty list of no entities were found.
     *
     * @param transformer The transformer that should be matched with the experience eateries.
     * @return A list of experience entities that belongs to the given player transformer.
     */
    public List<ExperienceEntity> getExperienceEntities(PlayerTransformer transformer) {
        return experienceQueue.stream()
            .filter(entity -> entity.getUserId() == transformer.getUserId() && entity.getGuildId() == transformer.getGuildId())
            .collect(Collectors.toList());
    }

    /**
     * Gets the level up channel from the current message event and guild
     * transformer, if no valid level up channel is set for the given
     * guild, the message text chanel will be returned instead.
     *
     * @param message The message event that should be used as a default.
     * @param guild   The guild transformer that should be used to get the level up channel.
     * @return The level up channel if one is set, otherwise the text channel from the message object.
     */
    private TextChannel getLevelUpChannel(Message message, GuildTransformer guild) {
        String levelChannel = guild.getLevelChannel();
        if (levelChannel == null) {
            return message.getTextChannel();
        }

        TextChannel channel = message.getGuild().getTextChannelById(levelChannel);
        return channel == null ? message.getTextChannel() : channel;
    }

    private List<Role> getRoleRewards(Message message, GuildTransformer guild, long level) {
        List<Role> roles = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : guild.getLevelRoles().entrySet()) {
            if (entry.getKey() <= level) {
                Role role = message.getGuild().getRoleById(entry.getValue());
                if (role != null) {
                    roles.add(role);
                }
            }
        }
        return roles;
    }

    private String loadRandomLevelupMessage(GuildTransformer guild, boolean hasLevelupRole) {
        return (String) RandomUtil.pickRandom(
            I18n.getLocale(guild).getConfig().getStringList(hasLevelupRole ? "levelupRoleMessages" : "levelupMessages")
        );
    }

    private Object asKey(MessageReceivedEvent event) {
        return event.getGuild().getId() + ":" + event.getAuthor().getId();
    }
}
