package io.github.gk0wk.mcron;

import io.github.gk0wk.violet.config.ConfigManager;
import io.github.gk0wk.violet.config.ConfigUtil;
import io.github.gk0wk.violet.message.MessageManager;
import me.lucko.helper.Schedulers;
import me.lucko.helper.scheduler.Task;
import me.lucko.helper.terminable.Terminable;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class CronManager implements Terminable {
    private final ArrayList<CronCommand> tasks = new ArrayList<>();
    private final List<CronCommand> outdatedTasks = new ArrayList<>();

    // 1秒内即将执行的任务
    private final List<CronCommand> inTimeTasks = new ArrayList<>();

    // 为防止卡顿导致错过一些任务(没有执行，但是错过了判断，被误认为是已过期任务)
    // 有一个缓冲池，在1s~60s后即将执行的命令也会在这里，这样如果这些任务过期了会立即执行
    // p.s. 不会有人写每秒都会运行的程序吧...
    private final List<CronCommand> cacheInTimeTasks = new ArrayList<>();

    private final Task cronTask;
    public CronManager() {
        reload();
        cronTask = Schedulers.async().runRepeating(this::run, 0, 20);
    }

    protected void reload() {
        try {
            this.tasks.clear();
            this.cacheInTimeTasks.clear();
            this.inTimeTasks.clear();
            this.outdatedTasks.clear();

            MCron.getInstance().configManager.get("config.yml").getNode("schedule-tasks").getChildrenMap().forEach((key, value) -> {
                if (key instanceof String) {
                    List<String> commands = ConfigUtil.setListIfNull(value).getList(Object::toString);
                    addTask((String) key, commands.toArray(new String[0]));
                }
            });
        } catch (IOException | ConfigManager.UnknownConfigFileFormatException e) {
            e.printStackTrace();
        }
    }

    SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    protected void listCron(CommandSender sender) {
        MCron.getInstance().messageManager.printf(sender, "$msg.list-head$");
        tasks.forEach(task -> {
            MCron.getInstance().messageManager.printf(sender, "$msg.list-cron$",
                    task.expression.expressionString, dateFormatter.format(new Date(task.expression.getNextTime())));
            for (String command : task.commands) {
                MCron.getInstance().messageManager.printf(sender, "$msg.list-command$", command);
            }
            MCron.getInstance().messageManager.printf(sender, "");
        });
        outdatedTasks.forEach(task -> {
            MCron.getInstance().messageManager.printf(sender, "$msg.list-cron-outdated$",
                    task.expression.expressionString, dateFormatter.format(new Date(task.expression.getNextTime())));
            for (String command : task.commands) {
                MCron.getInstance().messageManager.printf(sender, "$msg.list-command$", command);
            }
            MCron.getInstance().messageManager.printf(sender, "");
        });
    }

    /**
     * 定时检查模块的计时器
     */
    private int secondsCounter = 1;
    /**
     * 计时器的动态上限
     */
    private int counterBorder = 1;

    /**
     * 定时任务检查，每秒运行
     */
    private void run()
    {
        // 小于计数上限，继续休眠
        if (secondsCounter < counterBorder) {
            secondsCounter++;
            return;
        }

        // 下一次任务的间隔毫秒数(不含失效任务)
        long nextMillisecond = Long.MAX_VALUE;

        // 当前时间戳
        long curMillisecond = System.currentTimeMillis();

        List<CronCommand> tmpOutdated = new ArrayList<>();

        // 遍历所有任务
        for (CronCommand task : this.tasks) {
            long tmp = task.expression.getNextTime();

            // 忽略失效的，并清理之
            if (tmp == 0) {
                tmpOutdated.add(task);
                // 如果失效任务在缓冲列表中，肯定是漏掉了，赶快执行
                if (this.cacheInTimeTasks.contains(task)) {
                    this.cacheInTimeTasks.remove(task);
                    this.inTimeTasks.add(task);
                }
                continue;
            }

            tmp = tmp - curMillisecond;

            // 统计下一次任务的间隔时长(不含失效任务)
            if (tmp < nextMillisecond) {
                nextMillisecond = tmp;
            }

            // 一秒内即将执行的任务进入执行队列
            if (tmp <= 1000) {
                this.inTimeTasks.add(task);
                // 并从缓冲列表移除
                this.cacheInTimeTasks.remove(task);
                continue;
            }

            // 1s~60s进入缓冲列表
            if (tmp <= 60000) {
                if (!this.cacheInTimeTasks.contains(task)) {
                    this.cacheInTimeTasks.add(task);
                }
                continue;
            }

            // 到这里的肯定是60秒开外的任务
            // 如果这些任务中有些任务出现在缓冲区中
            // 说明漏掉了，尽快执行
            if (this.cacheInTimeTasks.contains(task)) {
                this.cacheInTimeTasks.remove(task);
                this.inTimeTasks.add(task);
            }
        }

        // 清理失效任务
        if (!tmpOutdated.isEmpty()) {
            tmpOutdated.forEach(this.tasks::remove);
            this.outdatedTasks.addAll(tmpOutdated);
            tmpOutdated.clear();
        }

        // 执行一秒内到来的任务
        if (!this.inTimeTasks.isEmpty()) {
            Schedulers.sync().runLater(this::runInSecond, 20);
        }

        // 计算下次检查时间
        counterBorder = getIntervalSeconds(nextMillisecond);
        secondsCounter = 1;
    }

    /**
     * 一段时间所对应的秒数
     */
    private static final int SECONDS_OF_12HOUR = 43200;
    private static final int SECONDS_OF_6HOUR = 21600;
    private static final int SECONDS_OF_3HOUR = 10800;
    private static final int SECONDS_OF_1HOUR = 3600;
    private static final int SECONDS_OF_30MIN = 1800;
    private static final int SECONDS_OF_15MIN = 900;
    private static final int SECONDS_OF_8MIN = 480;
    private static final int SECONDS_OF_4MIN = 240;
    private static final int SECONDS_OF_MINUTE = 60;
    private static final int SECONDS_OF_15SEC = 15;
    private static final int SECONDS_OF_5SEC = 5;
    private static final int SECONDS_OF_SECOND = 1;

    /**
     * 一段时间所对应的毫秒数
     */
    private static final long MILLISECOND_OF_2DAY = 172800000;
    private static final long MILLISECOND_OF_1DAY = 86400000;
    private static final long MILLISECOND_OF_12HOUR = 43200000;
    private static final long MILLISECOND_OF_4HOUR = 14400000;
    private static final long MILLISECOND_OF_2HOUR = 7200000;
    private static final long MILLISECOND_OF_HOUR = 3600000;
    private static final long MILLISECOND_OF_30MIN = 1800000;
    private static final long MILLISECOND_OF_15MIN = 900000;
    private static final long MILLISECOND_OF_5MIN = 300000;
    private static final long MILLISECOND_OF_MINUTE = 60000;
    private static final long MILLISECOND_OF_30SEC = 30000;

    /**
     * 根据毫秒差获得合适的休眠间隔(秒为单位)
     * @param delta 毫秒差
     * @return 休眠间隔(秒为单位)
     */
    private int getIntervalSeconds(long delta)
    {
        // 小于30秒   - 每秒
        if (delta < MILLISECOND_OF_30SEC)
            return SECONDS_OF_SECOND;

        // 小于1分钟  - 每5秒
        if (delta < MILLISECOND_OF_MINUTE)
            return SECONDS_OF_5SEC;

        // 小于5分钟  - 每15秒
        if (delta < MILLISECOND_OF_5MIN)
            return SECONDS_OF_15SEC;

        // 小于15分钟 - 每1分钟
        if (delta < MILLISECOND_OF_15MIN)
            return SECONDS_OF_MINUTE;

        // 小于30分钟 - 每4分钟
        if (delta < MILLISECOND_OF_30MIN)
            return SECONDS_OF_4MIN;

        // 小于1小时 - 每8分钟
        if (delta < MILLISECOND_OF_HOUR)
            return SECONDS_OF_8MIN;

        // 小于2小时 - 每15分钟
        if (delta < MILLISECOND_OF_2HOUR)
            return SECONDS_OF_15MIN;

        // 小于4小时 - 每半小时
        if (delta < MILLISECOND_OF_4HOUR)
            return SECONDS_OF_30MIN;

        // 小于12小时 - 每1小时
        if (delta < MILLISECOND_OF_12HOUR)
            return SECONDS_OF_1HOUR;

        // 小于1天 - 每3小时
        if (delta < MILLISECOND_OF_1DAY)
            return SECONDS_OF_3HOUR;

        // 小于2天 - 每6小时
        if (delta < MILLISECOND_OF_2DAY)
            return SECONDS_OF_6HOUR;

        // 其他 - 每12小时
        return SECONDS_OF_12HOUR;
    }

    /**
     * 执行inTimeTask中的那些一秒内即将执行的任务
     */
    private void runInSecond()
    {
        CommandSender sender = Bukkit.getConsoleSender();
        MessageManager messageManager = MCron.getInstance().messageManager;
        this.inTimeTasks.forEach(task -> {
            for (String command : task.commands) {
                messageManager.info("§a§lRun Command: §r" + command);
                Bukkit.dispatchCommand(sender, command);
            }
        });
        this.inTimeTasks.clear();
    }

    @Override
    public void close() {
        cronTask.stop();
    }

    /**
     * 在任务池中添加一个任务
     * @param cronExpression cron表达式
     * @param commands 任务要执行的指令
     */
    protected void addTask(String cronExpression, String[] commands)
    {
        try {
            CronCommand task = new CronCommand(cronExpression, commands);
            this.tasks.add(task);
        } catch (Exception e) {
            MCron.getInstance().messageManager.warn(MCron.getInstance().messageManager.sprintf(
                    "$msg.invalid_expression$", cronExpression));
        }
    }

    static class CronCommand {
        public final CronExpression expression;
        public final String[] commands;

        public CronCommand(String expression, String[] commands)
        {
            this.expression = new CronExpression(expression);
            this.commands = commands;
        }
    }
}
