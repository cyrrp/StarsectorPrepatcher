package com.starsector.prepatcher.agent;
import com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge;
import com.fs.starfarer.api.util.Pair;
import java.nio.file.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;

public final class AoTDCleanDeficitCompatibilityTest {
  public static void main(String[] args) throws Exception {
    Path classFile=Path.of(args[0]);
    Path configFile=Path.of(args[1]);
    byte[] original=Files.readAllBytes(classFile);
    PrepatcherConfig config=PrepatcherConfig.load(configFile);
    PrepatcherTransformer transformer=new PrepatcherTransformer(config);
    byte[] transformed=transformer.transform(null,
      "com/fs/starfarer/api/impl/campaign/econ/impl/BaseIndustry",null,null,original);
    require(transformed!=null,"clean deficit patch did not apply");
    byte[] repeated=transformer.transform(null,
      "com/fs/starfarer/api/impl/campaign/econ/impl/BaseIndustry",null,null,transformed);
    require(repeated==null,"repeated transform was not idempotent");
    Loader loader=new Loader(AoTDCleanDeficitCompatibilityTest.class.getClassLoader());
    Class<?> type=loader.define(transformed);
    Object instance=type.getConstructor().newInstance();
    Object vanilla=type.getMethod("getMaxDeficit",String[].class)
      .invoke(instance,(Object)new String[]{"fuel"});
    require(((Pair<?,?>)vanilla).two.equals(7),"raw vanilla fallback changed");

    StarsectorPrepatcherRuntimeBridge.configure(config,Path.of("."));
    StarsectorPrepatcherRuntimeBridge.setAoTDEconomyRestoreCompletionContract(
      true,"compatibility-test");
    long caps=StarsectorPrepatcherRuntimeBridge.registerAoTDForkContract(
      "aotd_theory_of_toolbox",
      StarsectorPrepatcherRuntimeBridge.AOTD_CURRENT_FORK_VERSION,
      StarsectorPrepatcherRuntimeBridge.AOTD_CURRENT_DECLARED_CAPABILITIES,
      new Consumer<Object>() { public void accept(Object event) {} },
      new BiFunction<Object,Object,Object>() {
        public Object apply(Object industry,Object ids) {
          Pair<String,Integer> p=new Pair<>(); p.one="aotd"; p.two=39; return p;
        }
      },
      new Runnable() { public void run() {} });
    require((caps & StarsectorPrepatcherRuntimeBridge.AOTD_REQUIRED_CAPABILITIES)
        == StarsectorPrepatcherRuntimeBridge.AOTD_REQUIRED_CAPABILITIES,
      "production capabilities not negotiated: 0x"+Long.toHexString(caps));
    Object aotd=type.getMethod("getMaxDeficit",String[].class)
      .invoke(instance,(Object)new String[]{"fuel"});
    require(((Pair<?,?>)aotd).two.equals(39),"resolver path not active");
    require(type.getDeclaredMethod("spp$baseIndustryRawGetMaxDeficit",String[].class)!=null,
      "preserved raw method missing");
    System.out.println("OK clean deficit wrapper raw=7 resolver=39 caps=0x"
      +Long.toHexString(caps)+" bytes="+transformed.length);
  }
  private static void require(boolean c,String m){if(!c)throw new AssertionError(m);}
  private static final class Loader extends ClassLoader {
    Loader(ClassLoader p){super(p);} Class<?> define(byte[] b){return defineClass(
      "com.fs.starfarer.api.impl.campaign.econ.impl.BaseIndustry",b,0,b.length);}
  }
}
