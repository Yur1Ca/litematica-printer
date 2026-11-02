package probe;
import java.util.*; import org.spongepowered.asm.service.*;
public class Properties implements IGlobalPropertyService {
 record Key(String name) implements IPropertyKey{} private final Map<IPropertyKey,Object> values=new HashMap<>();
 public IPropertyKey resolveKey(String s){return new Key(s);} public <T>T getProperty(IPropertyKey k){return (T)values.get(k);}
 public void setProperty(IPropertyKey k,Object v){values.put(k,v);} public <T>T getProperty(IPropertyKey k,T d){return (T)values.getOrDefault(k,d);}
 public String getPropertyString(IPropertyKey k,String d){return String.valueOf(values.getOrDefault(k,d));}
}