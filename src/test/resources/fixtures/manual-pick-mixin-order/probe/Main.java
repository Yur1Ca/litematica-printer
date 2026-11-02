package probe;
import org.spongepowered.asm.launch.*; import org.spongepowered.asm.mixin.*; import org.spongepowered.asm.mixin.transformer.*;
import org.objectweb.asm.*;import org.objectweb.asm.tree.*;
public class Main {
 public static void main(String[] a)throws Exception{
  MixinBootstrap.init(); MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
  Mixins.addConfiguration("probe.mixins.json");
  var ctor=Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer").getDeclaredConstructor(); ctor.setAccessible(true);
  var t=(IMixinTransformer)ctor.newInstance(); byte[] input=Main.class.getClassLoader().getResourceAsStream("game/Target.class").readAllBytes();
  byte[] out=t.transformClassBytes("game.Target","game.Target",input);
  var node=new ClassNode(); new ClassReader(out).accept(node,0);
  for(var m:node.methods)if(m.name.equals("pick"))for(var i:m.instructions)if(i instanceof MethodInsnNode c)System.out.println(c.owner+"."+c.name);
  Class<?> target=new ClassLoader(Main.class.getClassLoader()){Class<?> define(){return defineClass("game.Target",out,0,out.length);}}.define();
  System.out.println("RESULT="+target.getMethod("pick").invoke(null));
 }
}