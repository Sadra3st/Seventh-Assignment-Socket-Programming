# Three Message Passing Methods - Quick Comparison

## Method 1: Plain String (`"LOGIN|user|pass"`)

### ✅ Pros
- Fast and simple
- No libraries needed
- Human-readable

### ❌ Cons
- Fragile (breaks if data contains `|`)
- No structure/schema
- Hard to extend

### 🔧 Parsing
```java
String[] parts = message.split("\\|");
```
## Method 2: Java Serialization
### ✅ Pros
- Perfect Java compatibility
- Handles complex objects
- Automatic serialization

### ❌ Cons
- Java only
- Binary format (not readable)
- Versioning issues

## Method 3: JSON
### ✅ Pros
- Universal support
- Human-readable
- Handles nested data
- Easy to debug

🌍 Cross-Language Example

# Python
```python
import json
data = json.loads('{"user":"name"}')
```
``` javascript
// JavaScript
const data = JSON.parse(message);
```