package gg.crystalized;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;

public class FakeScheduler implements Scheduler {
	public record CapturedTask(Object plugin, Runnable runnable, long repeat, TimeUnit unit) {
	}

	public final List<CapturedTask> tasks = new ArrayList<>();

	@Override
	public TaskBuilder buildTask(Object plugin, Runnable runnable) {
		return new FakeTaskBuilder(plugin, runnable);
	}

	@Override
	public TaskBuilder buildTask(Object plugin, Consumer<ScheduledTask> consumer) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Collection<ScheduledTask> tasksByPlugin(Object plugin) {
		return List.of();
	}

	public List<Runnable> repeatTasks(long interval, TimeUnit unit) {
		return tasks.stream().filter(t -> t.repeat() == interval && t.unit() == unit).map(CapturedTask::runnable)
				.toList();
	}

	private class FakeTaskBuilder implements TaskBuilder {
		private final Object plugin;
		private final Runnable runnable;
		private boolean recorded;

		FakeTaskBuilder(Object plugin, Runnable runnable) {
			this.plugin = plugin;
			this.runnable = runnable;
		}

		@Override
		public TaskBuilder delay(long time, TimeUnit unit) {
			return this;
		}

		@Override
		public TaskBuilder repeat(long time, TimeUnit unit) {
			record(time, unit);
			return this;
		}

		@Override
		public TaskBuilder clearDelay() {
			return this;
		}

		@Override
		public TaskBuilder clearRepeat() {
			return this;
		}

		@Override
		public ScheduledTask schedule() {
			// one-shot tasks are captured too, so tests can assert who owns them
			record(0L, null);
			return null;
		}

		private void record(long time, TimeUnit unit) {
			if (recorded) {
				return;
			}
			recorded = true;
			FakeScheduler.this.tasks.add(new CapturedTask(plugin, runnable, time, unit));
		}
	}
}
