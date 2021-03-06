/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.smartdata.agent;

import akka.actor.ActorIdentity;
import akka.actor.ActorRef;
import akka.actor.ActorSelection;
import akka.actor.ActorSystem;
import akka.actor.Cancellable;
import akka.actor.Identify;
import akka.actor.PoisonPill;
import akka.actor.Props;
import akka.actor.Terminated;
import akka.actor.UntypedActor;
import akka.japi.Procedure;
import akka.pattern.Patterns;
import akka.util.Timeout;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smartdata.AgentService;
import org.smartdata.conf.SmartConfKeys;
import org.smartdata.protocol.message.StatusReporter;
import org.smartdata.conf.SmartConf;
import org.smartdata.server.engine.cmdlet.agent.AgentConstants;
import org.smartdata.server.engine.cmdlet.agent.SmartAgentContext;
import org.smartdata.server.engine.cmdlet.agent.messages.AgentToMaster.RegisterNewAgent;
import org.smartdata.server.engine.cmdlet.agent.messages.MasterToAgent;
import org.smartdata.server.engine.cmdlet.agent.messages.MasterToAgent.AgentRegistered;
import org.smartdata.server.engine.cmdlet.agent.AgentUtils;
import org.smartdata.protocol.message.StatusMessage;
import org.smartdata.server.utils.GenericOptionsParser;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkNotNull;

public class SmartAgent implements StatusReporter {
  private static final String NAME = "SmartAgent";
  private final static Logger LOG = LoggerFactory.getLogger(SmartAgent.class);
  private ActorSystem system;
  private ActorRef agentActor;

  public static void main(String[] args) throws IOException {
    SmartAgent agent = new SmartAgent();

    SmartConf conf = (SmartConf) new GenericOptionsParser(new SmartConf(), args).getConfiguration();
    String[] masters = conf.getStrings(SmartConfKeys.SMART_AGENT_MASTER_ADDRESS_KEY);

    checkNotNull(masters);

    agent.start(AgentUtils.overrideRemoteAddress(ConfigFactory.load(AgentConstants.AKKA_CONF_FILE),
        conf.get(SmartConfKeys.SMART_AGENT_ADDRESS_KEY)),
        AgentUtils.getMasterActorPaths(masters), conf);
  }

  public void start(Config config, String[] masterPath, SmartConf conf) {
    system = ActorSystem.apply(NAME, config);
    agentActor = system.actorOf(Props.create(AgentActor.class, this, masterPath), getAgentName());
    final Thread currentThread = Thread.currentThread();
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        close();
        try {
          currentThread.join();
        } catch (InterruptedException e) {
          // Ignore
        }
      }
    });
    Services.init(new SmartAgentContext(conf, this));
    Services.start();
    system.awaitTermination();
  }

  public void close() {
    Services.stop();
    if (system != null && !system.isTerminated()) {
      LOG.info("Shutting down system {}", AgentUtils.getSystemAddres(system));
      system.shutdown();
    }
  }

  @Override
  public void report(StatusMessage status) {
    Patterns.ask(agentActor, status, Timeout.apply(5, TimeUnit.SECONDS));
  }

  private String getAgentName() {
    return "agent-" + UUID.randomUUID().toString();
  }

  static class AgentActor extends UntypedActor {
    private final static Logger LOG = LoggerFactory.getLogger(AgentActor.class);

    private final static FiniteDuration TIMEOUT = Duration.create(30, TimeUnit.SECONDS);
    private final static FiniteDuration RETRY_INTERVAL = Duration.create(2, TimeUnit.SECONDS);

    private MasterToAgent.AgentId id;
    private ActorRef master;
    private final SmartAgent agent;
    private final String[] masters;

    public AgentActor(SmartAgent agent, String[] masters) {
      this.agent = agent;
      this.masters = masters;
    }

    @Override
    public void onReceive(Object message) throws Exception {
      unhandled(message);
    }

    @Override
    public void preStart() {
      Cancellable findMaster = findMaster();
      getContext().become(new WaitForFindMaster(findMaster));
    }

    private Cancellable findMaster() {
      return AgentUtils.repeatActionUntil(getContext().system(),
          Duration.Zero(), RETRY_INTERVAL, TIMEOUT,
          new Runnable() {
            @Override
            public void run() {
              for (String m : masters) {
                final ActorSelection actorSelection = getContext().actorSelection(m);
                actorSelection.tell(new Identify(null), getSelf());
              }
            }
          }, new Shutdown(agent));
    }



    private class WaitForFindMaster implements Procedure<Object> {

      private final Cancellable findMaster;

      public WaitForFindMaster(Cancellable findMaster) {
        this.findMaster = findMaster;
      }

      @Override
      public void apply(Object message) throws Exception {
        if (message instanceof ActorIdentity) {
          ActorIdentity identity = (ActorIdentity) message;
          master = identity.getRef();
          if (master != null) {
            findMaster.cancel();
            Cancellable registerAgent =
                AgentUtils.repeatActionUntil(getContext().system(), Duration.Zero(),
                    RETRY_INTERVAL, TIMEOUT,
                    new SendMessage(master, RegisterNewAgent.getInstance()), new Shutdown(agent));
            LOG.info("Registering to master {}", master);
            getContext().become(new WaitForRegisterAgent(registerAgent));
          }
        }
      }
    }

    private class WaitForRegisterAgent implements Procedure<Object> {

      private final Cancellable registerAgent;

      public WaitForRegisterAgent(Cancellable registerAgent) {
        this.registerAgent = registerAgent;
      }

      @Override
      public void apply(Object message) throws Exception {
        if (message instanceof AgentRegistered) {
          AgentRegistered registered = (AgentRegistered) message;
          registerAgent.cancel();
          getContext().watch(master);
          AgentActor.this.id = registered.getAgentId();
          LOG.info("SmartAgent {} registered to {}",
              AgentActor.this.id,
              AgentUtils.getFullPath(getContext().system(), getSelf().path()));
          getContext().become(new Serve());
        }
      }
    }

    private class Serve implements Procedure<Object> {

      @Override
      public void apply(Object message) throws Exception {
        if (message instanceof AgentService.Message) {
          try {
            Services.dispatch((AgentService.Message) message);
          } catch (Exception e) {
            LOG.error(e.getMessage());
          }
        } else if (message instanceof StatusMessage) {
          master.tell(message, getSelf());
          getSender().tell("status reported", getSelf());
        } else if (message instanceof Terminated) {
          Terminated terminated = (Terminated) message;
          if (terminated.getActor().equals(master)) {
            LOG.warn("Lost contact with master {}. Try registering again...", getSender());
            getContext().become(new WaitForFindMaster(findMaster()));
          }
        }
      }
    }

    private class SendMessage implements Runnable {

      private final ActorRef to;
      private final Object message;

      public SendMessage(ActorRef to, Object message) {
        this.to = to;
        this.message = message;
      }

      @Override
      public void run() {
        to.tell(message, getSelf());
      }
    }

    private class Shutdown implements Runnable {

      private SmartAgent agent;

      public Shutdown(SmartAgent agent) {
        this.agent = agent;
      }

      @Override
      public void run() {
        getSelf().tell(PoisonPill.getInstance(), ActorRef.noSender());
        LOG.info("Failed to find master after {}; Shutting down...", TIMEOUT);
        agent.close();
      }
    }
  }
}
