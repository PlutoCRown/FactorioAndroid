package dalvik.annotation.optimization;
import java.lang.annotation.*;
/** Compile-only signature; Android supplies the runtime annotation. */
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
public @interface CriticalNative {}
