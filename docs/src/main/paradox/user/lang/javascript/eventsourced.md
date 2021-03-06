# Event sourcing

This page documents how to implement CloudState event sourced entities in JavaScript. For information on what CloudState event sourced entities are, please read the general @ref[Event sourcing](../../features/eventsourced.md) documentation first.

## Persistence types and serialization

Event sourced entities persist events and snapshots, and these need to be serialized when persisted. A snapshot is the current state of the entity, and so the entities state must be serializable. The most straight forward way to persist events and the state is to use protobufs. CloudState will automatically detect if an emitted event or snapshot is a protobuf, and serialize it as such. For other serialization options, including JSON, see @ref:[Serialization](serialization.md).

While protobufs are the recommended format for persisting events, it is recommended that you do not persist your services protobuf messages, rather, you should create new messages, even if they are identical to the services. While this may introduce some overhead in needing to convert from one type to the other, the reason for doing this is that it will allow the services public interface to evolve independently from its data storage format, which should be private.

For our shopping cart example, we'll create a new file called `domain.proto`, the name domain is selected to indicate that these are my applications domain objects:

@@snip [domain.proto](/docs/src/test/js/domain.proto)

In this file, the `Cart` message is the state, while `ItemAdded` and `ItemRemoved` are events. Note that the events are in past tense - events are facts, indisputable things that happened in the past. A fact never becomes false, once you've added an item to a shopping cart, it never becomes untrue that that item was added to the cart. It may be removed later, but that doesn't change the fact that it was added, it only changes the current state of the cart, not what happened in the past. The names of your events should always be in past tense, communicating the indisputable fact that they represent.

## Creating an entity

An event sourced entity can be created using the @extref:[EventSourced](jsdoc:EventSourced.html) class.

@@snip [shoppingcart.js](/docs/src/test/js/test/eventsourced/shoppingcart.js) { #entity-class }

Here we pass in the protobuf files that contain our service and our domain protocol, `shoppingcart.proto` and `domain.proto`. CloudState needs the protobuf file that your service lives in so that it can load it and read it. It also needs the protobuf file that your domain events and snapshots are in so that when it receives these events and snapshots from the proxy, and can know how to deserialize them.

We also pass in the fully qualified name of the service our event sourced entity implements, `example.shoppingcart.ShoppingCartService`. We also are specifying some options.

The `persistenceId` is used to namespace events in the journal, useful for when you share the same database between multiple entities. It defaults to `entity`, so it's a good idea to select one explicitly.

The `snapshotEvery` parameter controls how often snapshots are taken, so that the entity doesn't need to be recovered from the whole journal each time it's loaded. If left unset, it defaults to 100. Setting it to a negative number will result in snapshots never being taken. Typically, leaving it at the default is good enough, we only recommend changing it if you have specific data from performance tests to justify a change.

## Using protobuf types

When you pass an event or snapshot to CloudState to persist, it needs to know how to serialize that. Simply passing a regular object does not provide enough information to know how protobuf should serialize the objects. Hence, any event or snapshot types that you want to use, you have to lookup the protobuf type for, and then use the `create` method to create it.

The `EventSourced` class provides a helper method called @extref:[`lookupType`](jsdoc:EventSourced.html#lookupType) to facilitate this. So before implementing anything, we'll look up these types so we can use them later.

@@snip [shoppingcart.js](/docs/src/test/js/test/eventsourced/shoppingcart.js) { #lookup-type }

## Initial state

When there are no snapshots persisted for an entity (such as when the entity is first created), the entity needs to have an initial state. Note that event sourced entities are not explicitly created, they are implicitly created when a command arrives for them. Additionally, creating an entity doesn't mean anything is persisted, nothing is persisted until an event is created for that entity. So, if user "X" opens their shopping cart for the first time, an entity will be created, but it will have no events in the log yet, and just be in the initial state.

To create the initial state, we set the @extref:[`initial`](jsdoc:EventSourced.html#initial) callback. This takes the id of the entity being created, and returns a new empty state, in this case, an empty shopping cart:

@@snip [shoppingcart.js](/docs/src/test/js/test/eventsourced/shoppingcart.js) { #initial }

Note the use of `Cart.create()`, this creates a protobuf message using the `Cart` protobuf message type that we looked up earlier.

## Behavior

Now we need to define the behavior for our entity. The behavior consists of two parts, command handlers, and event handlers.

### Command handlers

A @extref:[command handler](jsdoc:EventSourced.html#~commandHandler) is a function that takes a command, the current state, and an @extref:[`EventSourcedCommandContext`](jsdoc:EventSourced.EventSourcedCommandContext.html). It implements a service call on the entities gRPC interface.

The command is the input message type for the gRPC service call. For example, the `GetCart` service call has an input type of `GetShoppingCart`, while the `AddItem` service call has an input type of `AddLineItem`. The command will be an object that matches the structure of these protobuf types.

The command handler must return a message of the same type as the output type of the gRPC service call, in the case of our `GetCart` command, this must be a `Cart` message. Note that unlike for the state and events, this message does not need to be created using a looked up protobuf message type - CloudState already knows the output type of the gRPC service call and so can infer it itself. It only has to be a plain JavaScript object that matches the structure of the protobuf type.

The following shows the implementation of the `GetCart` command handler. This command handler is a read-only command handler, it doesn't emit any events, it just returns some state:

@@snip [shoppingcart.js](/docs/src/test/js/test/eventsourced/shoppingcart.js) { #get-cart }

#### Emitting events

Commands that modify the state MUST do so by emitting events.

@@@ warning
The **only** way a command handler may modify its state is by emitting an event. Any modifications made directly to the state from the command handler will not be persisted, and will be lost as soon as the command handler finishes executing.
@@@

A command handler may emit an event by using the @extref:[`emit`](jsdoc:EventSourced.EventSourcedCommandContext.html#emit) method on the `EventSourcedCommandContext`.

Here's an example of a command handler that emits an event:

@@snip [shoppingcart.js](/docs/src/test/js/test/eventsourced/shoppingcart.js) { #add-item }

This command handler also validates the command, ensuring the quantity items added is greater than zero. Invoking @extref:[`fail`](jsdoc:EventSourced.EventSourcedCommandContext.html#fail) fails the command - this method throws so there's no need to explicitly throw an exception.

### Event handlers

An @extref:[event handler](jsdoc:EventSourced.html#~eventHandler) is invoked at two points, when restoring entities from the journal, before any commands are handled, and each time a new event is emitted. An event handlers responsibility is to update the state of the entity according to the event. Event handlers are the only place where its safe to mutate the state of the entity at all.

An event handler must be declared for each type of event that gets emitted. The type is defined by the protobuf message type in the case of protobuf events, or the `type` property on a JSON object in the case of JSON events. The mapping for these type names to functions will be discussed later, for now we'll just look at the functions.

Event handlers take the event they are handling, and the state, and must return the new state. The handler may update the existing state passed in, but it still has to return that state as its return value.

Here's an example event handler for the `ItemAdded` event:

@@snip [shoppingcart.js](/docs/src/test/js/test/eventsourced/shoppingcart.js) { #item-added }

### Setting the behavior

Once you have your command handler and event handler functions implemented, you can now set your behavior. The @extref:[behavior callback](jsdoc:EventSourced.html#~behaviorCallback) takes the current state of the entity, and returns an object with two properties, `commandHandlers` and `eventHandlers`. The callback may return different sets of handlers according to the current state, this will be explored more @ref:[a little later](#multiple-behaviors), for now we'll just implement an entity with one set of handlers.

The behavior callback can be set by setting the @extref:[`behavior`](jsdoc:EventSourced.html#behavior) property on the entity:

@@snip [shoppingcart.js](/docs/src/test/js/test/eventsourced/shoppingcart.js) { #behavior }

The command handlers are a mapping of the gRPC service call names to the command handler functions we implemented. Note the names, as in the gRPC convention for service call names, are upper cased.

The event handlers are a mapping of event names to the event handler functions that we implemented. The event names must match the type of the events that are being persisted. In the case of protobuf messages, this is either the fully qualified name of the protobuf message, or the unqualified name of the protobuf message. For JSON messages, this is the value of the `type` property in the message.

#### Multiple behaviors

In the examples above, our shopping cart entity only has one behavior. An entity may have different states, where command and event handling may differ according to the state it is currently in. While this could be implemented using if statements in the handlers, CloudState also provides multiple behavior support, so that an entity can change its behavior. This multiple behavior support allows implementing entities as finite state machines.

The entities behavior can be changed by returning different sets of handlers from the `behavior` callback after inspecting the state. This callback is invoked each time a handler is needed, so there's no need to explicitly transition behaviors.

In the example below, we show a shopping cart that also has a checkout command. Once checked out, the shopping cart no longer accepts any commands to add or remove items, its state and therefore behavior changes:

@@snip [shoppingcart.js](/docs/src/test/js/test/eventsourced/shoppingcart.js) { #multiple-behaviors }

## Starting the entity

If you only have a single entity, as a convenience, you can start it directly, by invoking the @extref:[`start`](jsdoc:EventSourced.html#start) method, like so:

@@snip [shoppingcart.js](/docs/src/test/js/test/eventsourced/shoppingcart.js) { #start }

Alternatively, you can add it to the `CloudState` server explicitly:

@@snip [shoppingcart.js](/docs/src/test/js/test/eventsourced/shoppingcart.js) { #add-entity }
