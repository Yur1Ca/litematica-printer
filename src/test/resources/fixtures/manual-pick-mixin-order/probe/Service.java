package probe;
import java.util.*; import java.io.*; import java.net.*;
import org.objectweb.asm.*; import org.objectweb.asm.tree.*;
import org.spongepowered.asm.service.*; import org.spongepowered.asm.launch.platform.container.*;
public class Service extends MixinServiceAbstract implements IClassProvider,IClassBytecodeProvider,IClassTracker,ITransformerProvider {
 public String getName(){return "probe";} public boolean isValid(){return true;}
 public org.spongepowered.asm.mixin.MixinEnvironment.Phase getInitialPhase(){return org.spongepowered.asm.mixin.MixinEnvironment.Phase.DEFAULT;}
 public IClassProvider getClassProvider(){return this;} public IClassBytecodeProvider getBytecodeProvider(){return this;}
 public ITransformerProvider getTransformerProvider(){return this;} public IClassTracker getClassTracker(){return this;}
 public IMixinAuditTrail getAuditTrail(){return null;} public IFeatureValidator getFeatureValidator(){return IFeatureValidator.ALLOW_ALL;} public IAdviceProvider getAdviceProvider(){return IAdviceProvider.GENERIC;}
 public Collection<String> getPlatformAgents(){return List.of();} public IContainerHandle getPrimaryContainer(){return new ContainerHandleVirtual("probe");}
 public InputStream getResourceAsStream(String n){return getClass().getClassLoader().getResourceAsStream(n);}
 public URL[] getClassPath(){return new URL[0];} public Class<?> findClass(String n)throws ClassNotFoundException{return findClass(n,false);}
 public Class<?> findClass(String n,boolean i)throws ClassNotFoundException{return Class.forName(n,i,getClass().getClassLoader());}
 public Class<?> findAgentClass(String n,boolean i)throws ClassNotFoundException{return findClass(n,i);}
 public ClassNode getClassNode(String n)throws IOException,ClassNotFoundException{return getClassNode(n,false,0);}
 public ClassNode getClassNode(String n,boolean t)throws IOException,ClassNotFoundException{return getClassNode(n,t,0);}
 public ClassNode getClassNode(String n,boolean t,int flags)throws IOException,ClassNotFoundException{try(var in=getResourceAsStream(n.replace('.','/')+".class")){if(in==null)throw new ClassNotFoundException(n);var c=new ClassNode();new ClassReader(in).accept(c,flags);return c;}}
 public Collection<ITransformer> getTransformers(){return List.of();} public Collection<ITransformer> getDelegatedTransformers(){return List.of();} public void addTransformerExclusion(String n){}
 public void registerInvalidClass(String n){} public boolean isClassLoaded(String n){return false;} public String getClassRestrictions(String n){return "";}
}