# RFC: Distributed In-Memory Key-Value Store

## 1. Objective
To design and implement a distributed, in-memory key-value store. The system must be built entirely from scratch, abandoning all heavy web frameworks, REST APIs, or JSON parsing libraries to maximize throughput and minimize latency. The implementation is strictly timeboxed to 20 hours of development effort.

## 2. Background & Motivation
Our application requires an extremely low-latency caching solution. Standard web development often abstracts away raw network coordination, but HTTP is incredibly heavy for a simple, high-throughput key-value store. Every standard HTTP request sends a giant block of text headers, and usually relies on expensive string parsing or JSON serialization. 

To achieve our performance goals and reduce network bandwidth, we will utilize raw TCP sockets and send the absolute bare minimum number of bytes required. The resulting application will store key-value pairs in memory, handle concurrent client requests safely, and maintain high availability through a dynamically elected leader and multiple followers.

## 3. High-Level Design

### 3.1 Architecture Overview
The system will consist of a Server cluster and a Smart Client library.
* **Storage Engine:** The core data structure will be an in-memory `ConcurrentHashMap`.
* **Networking Layer:** The server will utilize Java NIO (`ServerSocketChannel` and `ByteBuffer`) to manage TCP connections.
* **Distributed Topology:** The system will run across three instances (1 Leader, 2 Followers) on different ports to provide high availability and fault tolerance.

### 3.2 Concurrency Model
To support high-throughput parallel reads while ensuring data integrity during writes, we will wrap the underlying map operations with a `ReentrantReadWriteLock`. This guarantees that while multiple threads can read a key simultaneously, a thread executing a `PUT` operation will hold an exclusive lock, preventing data corruption.

### 3.3 Dynamic Leader Election
To handle Leader failures autonomously, the cluster will implement a simplified Heartbeat and Election mechanism:
* **Heartbeats:** The Leader periodically broadcasts a ping to the Followers to assert its dominance.
* **Failure Detection:** If a Follower does not receive a heartbeat within a specified timeout threshold, it assumes the Leader is dead.
* **Election:** The Follower initiates an election by broadcasting its node ID (or port number).
* **Promotion:** The surviving node with the highest ID wins the election, promotes itself to Leader, and begins broadcasting heartbeats to the remaining Follower.

### 3.4 The "Smart Client" Abstraction
To keep the infrastructure simple and avoid an extra network hop, we will not deploy a middleware load balancer. Instead, the `MiniRedisClient` Java library will hide the cluster complexity from the end user.
* **Routing:** The client maintains a list of the 3 node ports and uses a simple round-robin integer to distribute `GET` requests.
* **Redirect Handling:** If the client sends a `PUT` to a Follower node, that node will reject it and return a `0x09 NOT_LEADER` byte. When the client library gets that error, it intercepts it so your application code never sees it. It silently opens a new connection to the correct node and retries the request.
* **Topology Map:** Most importantly, the client library updates its own internal map of the cluster.

## 4. Implementation Details: Custom Wire Protocol
Because we are avoiding HTTP and JSON, we must define a custom binary wire protocol to serialize and deserialize messages over TCP.

**Protocol Structure:**
* **Byte 0 (Command Type):** `0x01` PUT, `0x02` GET, `0x03` HEARTBEAT, `0x04` ELECTION_REQUEST, `0x09` NOT_LEADER (Redirect).
* **Bytes 1-4 (Int):** Length of the Key (K_LEN).
* **Bytes 5 to (5 + K_LEN):** The Key (UTF-8 encoded string).
* *(For PUT operations only)*
  * **Next 4 Bytes (Int):** Length of the Value (V_LEN).
  * **Remaining Bytes:** The Value payload.

## 5. Trade-offs and Constraints
Given the aggressive 20-hour scoping constraint for this sprint, the following trade-offs are accepted:
* **Split-Brain Risk:** In a network partition scenario where nodes can communicate with the client but not each other, multiple nodes might elect themselves Leader. We accept this lack of strict consensus (like a full Raft implementation would provide) to keep the project feasible within the allotted time.
* **Eventual Consistency:** Because replication to the Followers is asynchronous, there is a small window where a read against a Follower might return stale data immediately after a write to the Leader.
* **No Persistence:** Data is stored purely in-memory and will be lost if all three processes are terminated. Writing to disk is out of scope for V1.

## 6. Execution Timeline & Testing Strategy
The 20-hour allocation is divided into four distinct phases, each with its own testing requirements.

* **Phase 1 (5 hrs): Core Storage**
  * **Implementation:** Implement the thread-safe storage engine using `ConcurrentHashMap` and `ReentrantReadWriteLock` in a single-threaded environment.
  * **Testing:** Write unit tests using multiple worker threads to blast the map with simultaneous reads and writes. Verify that no data corruption occurs and that lock acquisition functions properly.
* **Phase 2 (5 hrs): Networking**
  * **Implementation:** Implement the Java NIO server, `ByteBuffer` parsing, and the custom binary wire protocol.
  * **Testing:** Since standard tools like curl won't work, we will write a quick Python test script using the `struct` library (or a basic Java `DataOutputStream`) to push raw bytes directly through a TCP socket. We will verify the server parses the payload and sends back a correct acknowledgment byte.
* **Phase 3 (6-8 hrs): Cluster Coordination**
  * **Implementation:** Spin up three instances. Implement the heartbeat mechanism, failure detection, and dynamic leader election.
  * **Testing:** Run three local instances. Send data to the Leader, manually kill the Leader process, and monitor the terminal logs to verify that the Followers detect the dropped heartbeats and successfully promote the correct node.
* **Phase 4 (2-4 hrs): Smart Client & Polish**
  * **Implementation:** Wire up the `MiniRedisClient.java` library, handle network edge cases and `NOT_LEADER` redirects.
  * **Testing:** Write an end-to-end integration test where the `MiniRedisClient` deliberately attempts a `PUT` request against a known Follower. Assert that the client catches the `0x09` byte, automatically redirects to the new Leader, and successfully persists the data without throwing an exception to the calling application.
