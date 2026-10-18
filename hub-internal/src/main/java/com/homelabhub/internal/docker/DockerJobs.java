package com.homelabhub.internal.docker;

import jakarta.annotation.PreDestroy;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Long Docker operations (pull, deploy, update, run) run here in the background while the page polls their output. At
 * most one job per host at a time, output capped, the last {@value #KEEP} jobs remembered. Threads time out when idle.
 */
@Component
class DockerJobs {

    static final int KEEP = 30;
    static final int MAX_OUTPUT = 256 * 1024;

    enum State { running, ok, failed }

    static final class Job {
        final String id;
        final String hostId;
        final String title;
        final Instant started = Instant.now();
        private final StringBuilder output = new StringBuilder();
        private volatile State state = State.running;
        private volatile Instant ended;
        private volatile String message;
        private boolean truncated;

        Job(String id, String hostId, String title) {
            this.id = id;
            this.hostId = hostId;
            this.title = title;
        }

        synchronized void append(String s) {
            if (output.length() + s.length() > MAX_OUTPUT) {
                truncated = true;
                output.delete(0, Math.min(output.length(), s.length() + MAX_OUTPUT / 8));
            }
            output.append(s);
        }

        void finish(boolean ok, String message) {
            this.message = message;
            this.ended = Instant.now();
            this.state = ok ? State.ok : State.failed;
        }

        State state() {
            return state;
        }

        synchronized Map<String, Object> view() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("hostId", hostId);
            m.put("title", title);
            m.put("state", state.name());
            m.put("started", started.toString());
            m.put("ended", ended == null ? null : ended.toString());
            m.put("message", message);
            m.put("output", (truncated ? "[earlier output dropped]\n" : "") + output);
            return m;
        }
    }

    interface Work {
        /** @return the message shown when it ends; throw DockerException to fail with a message */
        String run(Consumer<String> output) throws Exception;
    }

    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(10), r -> {
        Thread t = new Thread(r, "docker-job");
        t.setDaemon(true);
        return t;
    });

    DockerJobs() {
        pool.allowCoreThreadTimeOut(true);
    }

    synchronized Job start(String hostId, String title, Work work, Consumer<Job> onEnd) {
        for (Job j : jobs.values()) {
            if (j.hostId.equals(hostId) && j.state() == State.running) {
                throw new DockerException(HttpStatus.CONFLICT, "busy", "'" + j.title + "' is still running on this host. Wait for it to finish.");
            }
        }
        byte[] b = new byte[12];
        random.nextBytes(b);
        Job job = new Job(Base64.getUrlEncoder().withoutPadding().encodeToString(b), hostId, title);
        jobs.put(job.id, job);
        while (jobs.size() > KEEP) {
            String oldest = null;
            for (Job j : jobs.values()) {
                if (j.state() != State.running) {
                    oldest = j.id;
                    break;
                }
            }
            if (oldest == null) {
                break;
            }
            jobs.remove(oldest);
        }
        try {
            pool.execute(() -> {
                try {
                    String msg = work.run(job::append);
                    job.finish(true, msg);
                } catch (DockerException e) {
                    job.finish(false, e.getMessage());
                } catch (Exception e) {
                    job.finish(false, "The job failed unexpectedly.");
                } finally {
                    if (onEnd != null) {
                        onEnd.accept(job);
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            jobs.remove(job.id);
            throw new DockerException(HttpStatus.TOO_MANY_REQUESTS, "busy", "Too many Docker jobs are queued. Try again shortly.");
        }
        return job;
    }

    synchronized Job get(String id) {
        return jobs.get(id);
    }

    synchronized List<Map<String, Object>> recent() {
        List<Map<String, Object>> out = new ArrayList<>();
        jobs.values().forEach(j -> {
            Map<String, Object> v = j.view();
            v.remove("output");
            out.add(0, v);
        });
        return out;
    }

    @PreDestroy
    void stop() {
        pool.shutdownNow();
    }
}
