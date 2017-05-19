# modes for mortar
## server <-> server (syncronization)
a file is placed or removed in a directory on either server
the file change is replicated to the other server.
This is the mode ATLAS filesystem replication is handled, by finding the minimum spanning tree and propogating to maximize propogation speed
## server <-> server (syncronization and backup)
same as above, but after the transfer is complete, a backup checkpoint is created on either (or both) sides
## client -> server (unencrypted backup)
A client requests a backup, and the server pulls the files over to its local copy and creates a checkpoint
this is one half of the above, with different semantics (remote rdiff-backup vs rsync then local rdiff-backup)
## client -> server (encrypted backup)
A client requests a backup, and encrypted files are transferred over
