import React from "react";
import "./App.css";
import { withRouter } from "react-router";
import {
  BrowserRouter as Router,
  Switch,
  Route,
  Link,
  NavLink,
} from "react-router-dom";
import * as firebase from "firebase/app";
import { loadStripe } from "@stripe/stripe-js";
import marked from "marked";
import { Form, Input, Button, Spin, message } from "antd";
import { LoadingOutlined } from "@ant-design/icons";
import howWasYourDayImg from "./images/step-how-was-your-day.png";
import summaryImg from "./images/step-summary.png";

// Set up Firebase
import "firebase/auth";
import "firebase/database";
firebase.initializeApp({
  apiKey: "AIzaSyCBl-YAARDZuf0KcTIOTcmUZjynaKC7puc",
  authDomain: "journaltogether.firebaseapp.com",
  databaseURL: "https://journaltogether.firebaseio.com",
  projectId: "journaltogether",
  storageBucket: "journaltogether.appspot.com",
  messagingSenderId: "625725315087",
  appId: "1:625725315087:web:a946ce7cf0725381df2b7a",
  measurementId: "G-GYBL76DZQW",
});

const STRIPE_PK =
  process.env.NODE_ENV === "production"
    ? "pk_live_erHEE5TRgYAlgqMkadDK7zmA"
    : "pk_test_1iGrM3ZC85K4LbvyaphPMBr6";

const stripePromise = loadStripe(STRIPE_PK);

function serverPath(path) {
  const root =
    process.env.NODE_ENV === "development" ? "http://localhost:8080" : "";
  return `${root}/${path}`;
}

function FullScreenSpin({ message }) {
  return (
    <div className="Full-Screen-Loading-container">
      <div className="Full-Screen-Loading">
        <Spin
          tip={message}
          indicator={<LoadingOutlined style={{ fontSize: 24 }} spin />}
        />
      </div>
    </div>
  );
}

class SignIn extends React.Component {
  constructor(props) {
    super(props);
    this.state = {
      hasRequestedCode: false,
    };
  }
  componentDidMount() {
    this._emailInput && this._emailInput.focus();
  }
  render() {
    const { hasRequestedCode } = this.state;
    if (hasRequestedCode) {
      return (
        <div className="Sign-in-success-container">
          <div className="Sign-in-success">
            <h1 className="Sign-in-success-title">📝 journaltogether</h1>
            <h2 className="Sign-in-success-sub">Check your mail 😊</h2>
            <p className="Sign-in-success-content">
              Oky doke, you should receive an email from journal-signup. Click
              the link in there to login
            </p>
          </div>
        </div>
      );
    }
    return (
      <div className="Sign-in-root">
        <div className="Sign-in-header-and-form">
          <div className="Sign-in-header">
            <h1 className="Sign-in-header-title">📝 journaltogether</h1>
            <h2 className="Sign-in-header-sub">Let's get you signed in</h2>
            <p className="Sign-in-header-content">
              Enter your email, and you'll receive a magic link to sign in
            </p>
          </div>
          <div className="Sign-in-form">
            <Form
              onFinish={({ email }) => {
                this.setState({ hasRequestedCode: true });
                fetch(serverPath("api/magic/request"), {
                  method: "POST",
                  headers: { "Content-Type": "application/json" },
                  body: JSON.stringify({ email }),
                })
                  .then((x) =>
                    x.status === 200 ? x.json() : Promise.reject(x.json())
                  )
                  .catch(() => {
                    message.error(
                      "Oh no, something broke. Please try sending the magic link again"
                    );
                    this.setState({
                      hasRequestedCode: false,
                    });
                  });
              }}
            >
              <Form.Item
                name="email"
                rules={[{ required: true, message: "Please enter your email" }]}
              >
                <Input
                  className="Sign-in-form-input"
                  type="email"
                  placeholder="Enter your email"
                  size="large"
                  ref={(ref) => {
                    this._emailInput = ref;
                  }}
                />
              </Form.Item>
              <Form.Item>
                <Button
                  className="Sign-in-form-submit-btn"
                  size="large"
                  type="primary"
                  htmlType="submit"
                >
                  Send me a magic code 🚀
                </Button>
              </Form.Item>
            </Form>
          </div>
        </div>
      </div>
    );
  }
}

class ProfileHome extends React.Component {
  constructor(props) {
    super(props);
    this.state = {
      errorMessage: null,
      idToGroup: {},
    };
    this._idToGroupRef = {};
    this._userGroupIdsRef = firebase
      .database()
      .ref(`/users/${firebase.auth().currentUser.uid}/groups`);
  }
  componentDidMount() {
    const updateGroups = (f) => {
      this.setState(({ idToGroup }) => ({
        idToGroup: f(idToGroup),
      }));
    };
    const onGroup = (snap) => {
      updateGroups((oldGroups) => ({ ...oldGroups, [snap.key]: snap.val() }));
    };
    this._userGroupIdsRef.on("child_added", (snap) => {
      const groupId = snap.key;
      if (this._idToGroupRef[groupId]) return;
      const newRef = firebase.database().ref(`/groups/${groupId}/`);
      newRef.on("value", onGroup);
      this._idToGroupRef[groupId] = newRef;
    });
    this._userGroupIdsRef.on("child_removed", (snap) => {
      const groupId = snap.key;
      if (!this._idToGroupRef[groupId]) return;
      this._idToGroupRef[groupId].off();
      delete this._idToGroupRef[groupId];
      updateGroups((oldGroups) => {
        const res = { ...oldGroups };
        delete res[groupId];
        return res;
      });
    });
  }

  componentWillUnmount() {
    this._userGroupIdsRef.off();
    Object.values(this._idToGroupRef).forEach((ref) => ref.off());
  }

  render() {
    const { idToGroup, errorMessage } = this.state;
    if (errorMessage) {
      return errorMessage;
    }
    return (
      <div>
        <h2>Groups</h2>
        {Object.entries(idToGroup).map(([k, g]) => {
          return (
            <div key={k}>
              <h4>
                {g.name}{" "}
                {
                  <button
                    onClick={() => {
                      const userKeysToDelete =
                        (g.users &&
                          Object.keys(g.users).reduce((res, uk) => {
                            res[`/users/${uk}/groups/${k}`] = null;
                            return res;
                          }, {})) ||
                        {};
                      firebase
                        .database()
                        .ref()
                        .update({
                          [`/groups/${k}`]: null,
                          ...userKeysToDelete,
                        });
                    }}
                  >
                    delete group!
                  </button>
                }
              </h4>
              <div>
                {g.users &&
                  Object.entries(g.users).map(([uk, u], i, arr) => {
                    return (
                      <p key={u.email}>
                        {u.email}{" "}
                        {arr.length > 1 && (
                          <button
                            onClick={() => {
                              firebase
                                .database()
                                .ref()
                                .update({
                                  [`/groups/${k}/users/${uk}`]: null,
                                  [`/users/${uk}/groups/${k}`]: null,
                                });
                            }}
                          >
                            delete user!
                          </button>
                        )}
                      </p>
                    );
                  })}
              </div>
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  const email = this._friendEmailInput.value;
                  this._friendEmailInput.value = "";
                  const invitationRef = firebase
                    .database()
                    .ref("/invitations")
                    .push();
                  alert("ok, sent! this will be a cool flash message later");
                  invitationRef
                    .set({
                      "sender-email": firebase.auth().currentUser.email,
                      "receiver-email": email,
                      "group-id": k,
                    })
                    .then(() => {
                      fetch(serverPath("api/me/invite-user"), {
                        method: "POST",
                        headers: {
                          "Content-Type": "application/json",
                        },
                        body: JSON.stringify({
                          "invitation-id": invitationRef.key,
                        }),
                      }).then((x) => {
                        return x.status === 200
                          ? x.json()
                          : Promise.reject(x.json());
                      });
                    })
                    .then(
                      () => {},
                      () => {
                        this.setState({
                          errorMessage: "oi...failed invite user",
                        });
                      }
                    );
                }}
              >
                <input
                  placeholder="your friend's email"
                  ref={(x) => {
                    this._friendEmailInput = x;
                  }}
                />
                <button type="submit">invite</button>
              </form>
              <hr />
            </div>
          );
        })}
        <form
          onSubmit={(e) => {
            e.preventDefault();
            const name = this._groupNameInput.value;
            this._groupNameInput.value = "";
            const groupKey = firebase.database().ref("/groups/").push().key;
            const uid = firebase.auth().currentUser.uid;
            const email = firebase.auth().currentUser.email;
            firebase
              .database()
              .ref()
              .update({
                [`/groups/${groupKey}/name`]: name,
                [`/groups/${groupKey}/users/${uid}/email`]: email,
                [`/users/${uid}/groups/${groupKey}`]: true,
              });
          }}
        >
          <input label="group name" ref={(x) => (this._groupNameInput = x)} />
          <button type="submit">create a group</button>
        </form>
      </div>
    );
  }
}

class Journals extends React.Component {
  constructor(props) {
    super(props);
    this.state = {
      isLoadingJournals: true,
      errorMessage: null,
      isLoadingLevel: true,
      journals: null,
      level: null,
    };
  }
  componentDidMount() {
    firebase
      .database()
      .ref(`/users/${firebase.auth().currentUser.uid}/level`)
      .on("value", (snap) =>
        this.setState({ isLoadingLevel: false, level: snap.val() })
      );
    firebase
      .database()
      .ref(`/entries/${firebase.auth().currentUser.uid}/`)
      .on(
        "value",
        (snap) => {
          this.setState({
            journals: snap.val(),
            isLoadingJournals: false,
          });
        },
        (err) => {
          console.log(`/entries/${firebase.auth().currentUser.uid}/`);
          this.setState({ errorMessage: "oi can't access journals" });
        }
      );
  }

  render() {
    const {
      isLoadingJournals,
      isLoadingLevel,
      errorMessage,
      journals,
      level,
    } = this.state;
    if (errorMessage) {
      return <div>{errorMessage}</div>;
    }
    if (isLoadingJournals) {
      return <div>Loading...</div>;
    }
    const journalEntries = Object.entries(journals || {});
    if (journalEntries.length === 0) {
      return (
        <div>You don't have journals yet. You'll see them here soon : ]</div>
      );
    }
    return (
      <div>
        {journalEntries
          .sort(([ka, _a], [kb, _b]) => +kb - +ka)
          .map(([k, j]) => {
            return (
              <div key={k}>
                <button
                  onClick={(e) => {
                    firebase
                      .database()
                      .ref(`/entries/${firebase.auth().currentUser.uid}/${k}`)
                      .set(null);
                  }}
                >
                  delete
                </button>
                <h3>
                  {new Date(j["date"]).toLocaleString("en-US", {
                    weekday: "short",
                    month: "long",
                    day: "numeric",
                    hour: "numeric",
                  })}
                </h3>
                <div
                  dangerouslySetInnerHTML={{
                    __html: marked(j["stripped-text"]),
                  }}
                ></div>
                <hr />
              </div>
            );
          })}
        {!isLoadingLevel && level !== "premium" ? (
          <div>
            <h3>
              Want to access journals gr 1 month? Upgrade to premium!{" "}
              <Link to="/me/account">Learn more</Link>
            </h3>
          </div>
        ) : null}
      </div>
    );
  }
}

class Account extends React.Component {
  constructor(props) {
    super(props);
    this.state = {
      isLoading: true,
      level: null,
    };
  }
  componentDidMount() {
    firebase
      .database()
      .ref(`/users/${firebase.auth().currentUser.uid}/level`)
      .on("value", (snap) =>
        this.setState({ isLoading: false, level: snap.val() })
      );
  }
  render() {
    const { isLoading, errorMessage, level } = this.state;
    if (errorMessage) {
      return <div>{errorMessage}</div>;
    }
    if (isLoading) {
      return <div>Loading...</div>;
    }
    const deleteAcc = (
      <h3>
        Wanna delete your account instead?
        <button
          onClick={(e) => {
            firebase
              .auth()
              .currentUser.getIdToken()
              .then((token) => {
                return fetch(serverPath("api/me/delete-account"), {
                  method: "POST",
                  headers: {
                    "Content-Type": "application/json",
                    token: token,
                  },
                });
              })
              .then((x) => {
                firebase.auth().signOut();
                alert("oky doke you are deleted");
                return x.status === 200 ? x.json() : Promise.reject(x.json());
              });
          }}
        >
          Delete Account
        </button>
      </h3>
    );
    if (level === "premium") {
      return (
        <div>
          You are a premium member!{" "}
          <button
            onClick={(e) => {
              this.setState({ isLoading: true });
              firebase
                .auth()
                .currentUser.getIdToken()
                .then((token) => {
                  return fetch(
                    serverPath("api/me/checkout/cancel-subscription"),
                    {
                      method: "POST",
                      headers: {
                        "Content-Type": "application/json",
                        token: token,
                      },
                    }
                  );
                })
                .then((x) => {
                  return x.status === 200 ? x.json() : Promise.reject(x.json());
                })
                .then(
                  (x) => this.setState({ isLoading: false }),
                  (e) => {
                    debugger;
                    this.setState({
                      isLoading: false,
                      errorMessage: "Oh boy. can't downgrade ya.",
                    });
                  }
                );
            }}
          >
            downgrade
          </button>
          <br />
          {deleteAcc}
        </div>
      );
    }
    return (
      <div>
        <h1>
          Hey, want to upgrade?{" "}
          <button
            onClick={() => {
              this.setState({ isLoading: true });
              const sessionPromise = firebase
                .auth()
                .currentUser.getIdToken()
                .then((token) => {
                  return fetch(serverPath("api/me/checkout/create-session"), {
                    method: "POST",
                    headers: {
                      "Content-Type": "application/json",
                      token: token,
                    },
                  });
                })
                .then((x) => {
                  return x.status === 200 ? x.json() : Promise.reject(x.json());
                });
              Promise.all([stripePromise, sessionPromise]).then(
                ([stripe, session]) => {
                  stripe.redirectToCheckout({ sessionId: session["id"] });
                },
                (e) => {
                  this.setState({
                    isLoading: false,
                    errorMessage: "Oh boy. can't upgrade ya.",
                  });
                }
              );
            }}
          >
            Click here to upgrade to premium
          </button>
        </h1>
        {deleteAcc}
      </div>
    );
  }
}

function CheckoutSuccess() {
  return <div>Baam you're signed up :)</div>;
}

class MeComp extends React.Component {
  constructor(props) {
    super(props);
    this.state = {
      isLoading: true,
      isLoggedIn: null,
    };
  }

  componentDidMount() {
    window.signOut = () => firebase.auth().signOut();
    firebase.auth().onAuthStateChanged((user) => {
      this.setState({
        isLoading: false,
        isLoggedIn: user,
      });
    });
  }

  render() {
    const { isLoading, isLoggedIn } = this.state;
    if (isLoading) {
      return <FullScreenSpin />;
    }
    if (!isLoggedIn) {
      return <SignIn />;
    }
    return (
      <div className="Me-container">
        <div className="Me-header">
          <h2 className="Me-header-logo">
            <NavLink className="Me-header-logo-link" to="/me">
              📝 journaltogether
            </NavLink>
          </h2>
          <div className="Me-header-menu-container">
            <NavLink exact className="Me-header-menu-item" to="/me">
              Home
            </NavLink>
            <NavLink className="Me-header-menu-item" to="/me/journals">
              Journals
            </NavLink>
            <NavLink className="Me-header-menu-item" to="/me/account">
              Account
            </NavLink>
            <a
              className="Me-header-menu-item"
              href="#"
              onClick={() => firebase.auth().signOut()}
            >
              Sign out
            </a>
          </div>
        </div>
        <Switch>
          <Route path="/me/journals">
            <Journals />
          </Route>
          <Route path="/me/account">
            <Account />
          </Route>
          <Route path="/me/checkout/success">
            <CheckoutSuccess />
          </Route>
          <Route path="/me">
            <ProfileHome />
          </Route>
        </Switch>
      </div>
    );
  }
}

const Me = withRouter(MeComp);

class MagicAuthComp extends React.Component {
  constructor(props) {
    super(props);
    this.state = {
      isLoading: true,
      errorMessage: null,
    };
  }

  componentDidMount() {
    const { code } = this.props.match.params;
    fetch(serverPath("api/magic/auth"), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code }),
    })
      .then((x) => (x.status === 200 ? x.json() : Promise.reject("uh oh")))
      .then(({ token }) => firebase.auth().signInWithCustomToken(token))
      .then(
        () => {
          this.props.history.push("/me");
        },
        () => {
          this.props.history.push("/me");
          message.error(
            "This magic link did not seem to work. Please try again"
          );
        }
      );
  }

  render() {
    const { isLoading, errorMessage } = this.state;
    if (isLoading) {
      return <FullScreenSpin message="Signing in..." />;
    }
    return null;
  }
}

const MagicAuth = withRouter(MagicAuthComp);

function HomeComp({ history }) {
  return (
    <div className="Home">
      <div className="Home-menu">
        <div className="Home-menu-item-container">
          <a
            className="Home-menu-item"
            href="mailto:stepan.p@gmail.com"
            target="_blank"
          >
            Contact us
          </a>
          <Link className="Home-menu-item" to="/me">
            Sign in
          </Link>
        </div>
      </div>
      <div className="Home-hero">
        <div className="Home-hero-header">
          <h1 className="Home-hero-title">📝 journaltogether</h1>
          <h2 className="Home-hero-sub">
            Keep track of your days and connect with your friends
          </h2>
        </div>
        <div className="Home-hero-content">
          <p>
            Journal and keep tabs with your friends all over email. At the end
            of every day, you'll receive an email from Journal Together. The
            very next day, you'll receive a group email with your friends, with
            what everyone wrote. It's as simple as that
          </p>
        </div>
      </div>
      <div className="Home-steps-container">
        <div className="Home-step">
          <div className="Home-step-image-container"></div>
          <div className="Home-step-info">
            <h3 className="Home-step-title">Choose your friends</h3>
            <p>
              Who do you want to share a slice of life with? Sign up and pick
              your friends
            </p>
          </div>
        </div>
        <div className="Home-step">
          <div className="Home-step-image-container">
            <img className="Home-step-image" src={howWasYourDayImg} />
          </div>
          <div className="Home-step-info">
            <h3 className="Home-step-title">Journal over email</h3>
            <p>
              How did your day go? Journal Together will email you at the end of
              every day. Jot it down in any way you like.
            </p>
          </div>
        </div>
        <div className="Home-step">
          <div className="Home-step-image-container">
            <img className="Home-step-image" src={summaryImg} />
          </div>
          <div className="Home-step-info">
            <h3 className="Home-step-title">See how your friends are doing</h3>
            <p>
              At the beginning of the next day, you'll receive a group email
              with what all your friends wrote. Experience eachother's slice of
              life no matter where in the world you are.
            </p>
          </div>
        </div>
      </div>
      <div className="Home-faq-container">
        <div className="Home-faq-header">
          <h2 className="Home-faq-title">Frequently Asked Questions</h2>
        </div>
        <div className="Home-faq-section">
          <h3>Q: How much does this cost?</h3>
          <p>
            You are free to use Journal Together as long as you like.{" "}
            <strong>
              There is a premium membership that costs $10 a month
            </strong>
            . If you choose the premium membership, you'll have access to your
            journals since the beginning of time. A standard membership on shows
            the last one month's worth.
          </p>
        </div>
        <div className="Home-faq-section">
          <h3>Q: Can I just use this for myself?</h3>
          <p>
            Absolutely. If you choose to invite friends, you'll all receive a
            summary email. If you'd like to use this as a personal journal, you
            are free to do that. If you don't create any groups, you'll still be
            able to log and view your journals.
          </p>
        </div>
        <div className="Home-faq-section">
          <h3>Q: What will you do with my data?</h3>
          <p>
            These are your journal entries. We respect the heck out of that. I
            won't use or sell your data. If you report a bug, I may have to go
            through it. Whenever I do, I'll let you know. I'll put my{" "}
            <a
              href="https://www.linkedin.com/in/stepan-parunashvili-65698932/"
              target="_blank"
            >
              name
            </a>{" "}
            behind that.
          </p>
        </div>
      </div>
      <div className="Home-action-container">
        <div className="Home-action-header">
          <h2 className="Home-action-title">Yes, it is that easy</h2>
        </div>
        <p className="Home-action-sub">
          That's right. It's as simple as that. Ready to give it a whirl?
        </p>
        <Button
          className="Home-btn"
          type="primary"
          onClick={() => {
            history.push("/me");
          }}
        >
          Sign up free
        </Button>
      </div>
    </div>
  );
}

const Home = withRouter(HomeComp);

class App extends React.Component {
  render() {
    return (
      <div className="App">
        <Router>
          <Switch>
            <Route path="/me">
              <Me />
            </Route>
            <Route path="/magic/:code">
              <MagicAuth />
            </Route>
            <Route path="/">
              <Home />
            </Route>
          </Switch>
        </Router>
      </div>
    );
  }
}

export default App;
